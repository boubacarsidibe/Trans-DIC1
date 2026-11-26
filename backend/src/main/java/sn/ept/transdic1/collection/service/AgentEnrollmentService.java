package sn.ept.transdic1.collection.service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.audit.service.AuditService;
import sn.ept.transdic1.collection.dto.EnrollmentDtos.AgentEnrollRequest;
import sn.ept.transdic1.collection.dto.EnrollmentDtos.AgentEnrollResponse;
import sn.ept.transdic1.collection.dto.EnrollmentDtos.EnrollmentDecisionResult;
import sn.ept.transdic1.collection.dto.EnrollmentDtos.EnrollmentPrincipal;
import sn.ept.transdic1.collection.dto.EnrollmentDtos.EnrollmentRequestResponse;
import sn.ept.transdic1.common.scope.SiteScope;
import sn.ept.transdic1.inventory.domain.AgentEnrollment;
import sn.ept.transdic1.inventory.domain.AgentStatus;
import sn.ept.transdic1.inventory.domain.ApprovalMode;
import sn.ept.transdic1.inventory.domain.EnrollmentStatus;
import sn.ept.transdic1.inventory.domain.EnrollmentToken;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.EquipmentStatus;
import sn.ept.transdic1.inventory.repository.AgentEnrollmentRepository;
import sn.ept.transdic1.inventory.repository.CollectionAgentRepository;
import sn.ept.transdic1.inventory.repository.EnrollmentTokenRepository;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;

/**
 * Inscription automatique d'un agent par jeton partagé (ADR 0054), et décisions des administrateurs
 * sur les demandes en attente.
 *
 * <p>Toutes les issues d'une inscription — acceptée, en attente, refusée — sont rendues comme un
 * {@link Outcome} et non levées comme exception : la transaction est alors validée et l'entrée
 * d'audit du refus n'est pas annulée avec elle.
 */
@Service
public class AgentEnrollmentService {
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final String AUDIT_TARGET = "AGENT_ENROLLMENT";
  private static final int MAX_ADDRESS = 45;

  /** Issue d'une demande d'inscription : code HTTP et corps à renvoyer à l'agent. */
  public record Outcome(HttpStatus status, AgentEnrollResponse body) {}

  private final EnrollmentTokenRepository tokens;
  private final AgentEnrollmentRepository enrollments;
  private final EquipmentRepository equipments;
  private final CollectionAgentRepository agents;
  private final AgentRegistrationService registration;
  private final AuditService audit;
  private final SiteScope scope;
  private final Clock clock;
  private final int retrySeconds;

  public AgentEnrollmentService(
      EnrollmentTokenRepository tokens,
      AgentEnrollmentRepository enrollments,
      EquipmentRepository equipments,
      CollectionAgentRepository agents,
      AgentRegistrationService registration,
      AuditService audit,
      SiteScope scope,
      Clock clock,
      @Value("${collection.enrollment.retry-seconds:60}") int retrySeconds) {
    this.tokens = tokens;
    this.enrollments = enrollments;
    this.equipments = equipments;
    this.agents = agents;
    this.registration = registration;
    this.audit = audit;
    this.scope = scope;
    this.clock = clock;
    this.retrySeconds = Math.max(10, retrySeconds);
  }

  // --- Côté agent -------------------------------------------------------------------------------

  /**
   * Traite une demande d'inscription.
   *
   * <p>Ordre des contrôles : révocation et plage d'adresses valent pour toute demande ; expiration
   * et nombre d'utilisations ne valent que pour une demande qui consomme une utilisation (machine
   * nouvelle ou qui se réinscrit). Une machine approuvée qui vient chercher son jeton n'en consomme
   * pas : elle l'a fait en déposant sa demande, et l'approbation d'un administrateur ne doit pas
   * être perdue parce que le jeton a expiré entre-temps.
   */
  @Transactional
  public Outcome enroll(EnrollmentPrincipal principal, AgentEnrollRequest request, String source) {
    Instant now = clock.instant();
    var token = tokens.findForUpdate(principal.tokenId()).orElse(null);
    if (token == null) {
      return unauthorized();
    }
    String machineId = request.machineId().trim().toLowerCase(Locale.ROOT);
    UUID siteId = token.getSite().getId();
    String hostname = request.hostname().trim();

    if (token.isRevoked()) {
      return refuse(token, hostname, source, "jeton révoqué", HttpStatus.UNAUTHORIZED);
    }
    if (!allowedSource(token, source)) {
      return refuse(
          token, hostname, source, "adresse hors des plages autorisées", HttpStatus.FORBIDDEN);
    }

    var existing = enrollments.findBySiteIdAndMachineId(siteId, machineId);
    if (existing.isPresent()) {
      var record = existing.get();
      switch (record.getStatus()) {
        case APPROVED -> {
          describe(record, request, source, now);
          return issue(record, token, true, now);
        }
        case PENDING -> {
          // Une machine qui redemande ne consomme rien et n'encombre pas l'audit.
          describe(record, request, source, now);
          return pending();
        }
        case REJECTED -> {
          describe(record, request, source, now);
          return new Outcome(
              HttpStatus.FORBIDDEN,
              new AgentEnrollResponse(
                  "REFUSE", null, null, null, null, "Inscription refusée par un administrateur"));
        }
        case ENROLLED -> {
          // Réinstallation de la même machine : traitée comme une nouvelle utilisation.
        }
      }
    }

    if (token.isExpired(now)) {
      return refuse(token, hostname, source, "jeton expiré", HttpStatus.UNAUTHORIZED);
    }
    if (token.isExhausted()) {
      return refuse(
          token,
          hostname,
          source,
          "nombre maximal d'utilisations atteint",
          HttpStatus.UNAUTHORIZED);
    }
    token.consume(now);

    boolean again = existing.isPresent();
    var record =
        existing.orElseGet(
            () ->
                new AgentEnrollment(
                    token.getSite(), token, machineId, EnrollmentStatus.PENDING, now));
    record.requestAgain(token, EnrollmentStatus.PENDING, now);
    describe(record, request, source, now);
    enrollments.save(record);

    if (token.getApprovalMode() == ApprovalMode.APPROVAL) {
      audit.record(
          "AGENT_ENROLLMENT_PENDING",
          AUDIT_TARGET,
          record.getId(),
          details(token, hostname, source) + (again ? " reinscription=oui" : ""));
      return pending();
    }
    var equipment = equipmentFor(record, token);
    record.approve(equipment, null, now);
    return issue(record, token, false, now);
  }

  private Outcome issue(
      AgentEnrollment record, EnrollmentToken token, boolean approved, Instant now) {
    var equipment = record.getEquipment();
    if (equipment == null) {
      // L'équipement approuvé a été supprimé entre-temps : on le recrée.
      equipment = equipmentFor(record, token);
    }
    boolean reenrolled =
        agents.findByEquipmentIdAndStatus(equipment.getId(), AgentStatus.ACTIVE).isPresent();
    var agent = registration.register(equipment.getId());
    record.enrolled(equipment, now);
    enrollments.save(record);
    audit.record(
        "AGENT_ENROLLED",
        AUDIT_TARGET,
        record.getId(),
        details(token, record.getHostname(), record.getSourceAddress())
            + " equipement="
            + equipment.getInventoryCode()
            + " mode="
            + (approved ? "APPROVAL" : "AUTO")
            + (reenrolled ? " ancienJetonRevoque=oui" : ""));
    return new Outcome(
        HttpStatus.CREATED,
        new AgentEnrollResponse(
            "INSCRIT", equipment.getId(), agent.identifier(), agent.token(), null, null));
  }

  private Outcome pending() {
    return new Outcome(
        HttpStatus.ACCEPTED,
        new AgentEnrollResponse(
            "EN_ATTENTE",
            null,
            null,
            null,
            retrySeconds,
            "Demande en attente d'approbation par un administrateur"));
  }

  private static Outcome unauthorized() {
    return new Outcome(
        HttpStatus.UNAUTHORIZED,
        new AgentEnrollResponse("REFUSE", null, null, null, null, "Jeton d'inscription invalide"));
  }

  /**
   * Refus d'un jeton connu : la raison exacte va dans l'audit, l'agent ne reçoit qu'un message
   * générique (qui détient un jeton volé n'apprend pas s'il est expiré ou épuisé).
   */
  private Outcome refuse(
      EnrollmentToken token, String hostname, String source, String reason, HttpStatus status) {
    audit.record(
        "AGENT_ENROLLMENT_REFUSED",
        "ENROLLMENT_TOKEN",
        token.getId(),
        details(token, hostname, source) + " raison=" + reason);
    if (status == HttpStatus.FORBIDDEN) {
      return new Outcome(
          status, new AgentEnrollResponse("REFUSE", null, null, null, null, "Inscription refusée"));
    }
    return unauthorized();
  }

  private static String details(EnrollmentToken token, String hostname, String source) {
    return "jeton=" + token.getName() + " machine=" + hostname + " source=" + source;
  }

  private static boolean allowedSource(EnrollmentToken token, String source) {
    var ranges = AddressRange.parseAll(token.getAllowedRanges());
    return ranges.isEmpty() || ranges.stream().anyMatch(range -> range.contains(source));
  }

  private static void describe(
      AgentEnrollment record, AgentEnrollRequest request, String source, Instant now) {
    record.describe(
        truncate(request.hostname().trim(), 120),
        truncate(String.join(",", usableAddresses(request.ipAddresses())), 400),
        source == null ? null : truncate(source, MAX_ADDRESS),
        truncate(request.os(), 64),
        truncate(request.osVersion(), 120),
        truncate(request.agentVersion(), 32),
        now);
  }

  /** Adresses déclarées qui désignent vraiment la machine : ni boucle locale, ni lien local. */
  static List<String> usableAddresses(List<String> declared) {
    Set<String> kept = new LinkedHashSet<>();
    if (declared == null) {
      return List.of();
    }
    for (String address : declared) {
      var bytes = AddressRange.literal(address);
      if (bytes.isEmpty()) continue;
      try {
        var inet = java.net.InetAddress.getByAddress(bytes.get());
        if (inet.isLoopbackAddress() || inet.isLinkLocalAddress() || inet.isAnyLocalAddress()) {
          continue;
        }
        kept.add(inet.getHostAddress());
      } catch (java.net.UnknownHostException e) {
        // Impossible : les octets viennent d'une adresse littérale analysée.
      }
    }
    return new ArrayList<>(kept);
  }

  // --- Équipement
  // ---------------------------------------------------------------------------------

  /**
   * L'équipement de la machine : celui qu'elle avait déjà, sinon un équipement déjà inventorié à
   * l'adresse d'où elle se connecte (créé par une synchronisation d'inventaire, par exemple), sinon
   * un nouvel équipement.
   *
   * <p>L'adoption d'un équipement existant ne se fonde que sur l'adresse de la connexion TCP, que
   * l'agent ne peut pas choisir, et jamais sur les adresses qu'il déclare : sinon un jeton volé
   * permettrait de se greffer sur n'importe quel serveur du site.
   */
  private Equipment equipmentFor(AgentEnrollment record, EnrollmentToken token) {
    if (record.getEquipment() != null) {
      return record.getEquipment();
    }
    var site = token.getSite();
    Optional<Equipment> adoptable =
        Optional.ofNullable(record.getSourceAddress())
            .flatMap(equipments::findFirstByManagementAddress)
            .filter(e -> e.getSite() != null && e.getSite().getId().equals(site.getId()))
            .filter(e -> agents.findByEquipmentIdAndStatus(e.getId(), AgentStatus.ACTIVE).isEmpty())
            .filter(e -> !enrollments.existsByEquipmentId(e.getId()));
    if (adoptable.isPresent()) {
      var adopted = adoptable.get();
      audit.record(
          "EQUIPMENT_ADOPTED",
          "EQUIPMENT",
          adopted.getId(),
          "code=" + adopted.getInventoryCode() + " inscription=" + token.getName());
      return adopted;
    }
    var draft =
        new Equipment(
            inventoryCode(site.getCode()),
            truncate(record.getHostname(), 120),
            managementAddress(record),
            token.getEquipmentType(),
            EquipmentStatus.UNKNOWN,
            site);
    String os =
        record.getOsName() + (record.getOsVersion() == null ? "" : " " + record.getOsVersion());
    draft.setOperatingSystem(truncate(os, 120));
    // L'identifiant est attribué à la construction : save() fusionne et rend l'instance gérée.
    var created = equipments.save(draft);
    audit.record(
        "EQUIPMENT_CREATED",
        "EQUIPMENT",
        created.getId(),
        "code="
            + created.getInventoryCode()
            + " type="
            + created.getType()
            + " inscription="
            + token.getName());
    return created;
  }

  /**
   * Adresse de gestion : la première adresse déclarée encore libre (celle de la connexion en
   * premier si la machine la déclare), puis le nom d'hôte. L'adresse est unique dans l'inventaire.
   */
  private String managementAddress(AgentEnrollment record) {
    List<String> declared =
        record.getIpAddresses() == null || record.getIpAddresses().isBlank()
            ? List.of()
            : List.of(record.getIpAddresses().split(","));
    List<String> candidates = new ArrayList<>();
    if (record.getSourceAddress() != null && declared.contains(record.getSourceAddress())) {
      candidates.add(record.getSourceAddress());
    }
    candidates.addAll(declared);
    candidates.add(record.getHostname());
    for (String candidate : candidates) {
      if (!candidate.isBlank()
          && candidate.length() <= MAX_ADDRESS
          && equipments.findFirstByManagementAddress(candidate).isEmpty()) {
        return candidate;
      }
    }
    String suffix = "-" + record.getMachineId().substring(0, 8);
    return truncate(record.getHostname(), MAX_ADDRESS - suffix.length()) + suffix;
  }

  private String inventoryCode(String siteCode) {
    String prefix = truncate(siteCode == null ? "SITE" : siteCode, 24).toUpperCase(Locale.ROOT);
    for (int attempt = 0; attempt < 10; attempt++) {
      byte[] bytes = new byte[4];
      RANDOM.nextBytes(bytes);
      String code = prefix + "-AG-" + HexFormat.of().withUpperCase().formatHex(bytes);
      if (!equipments.existsByInventoryCode(code)) {
        return code;
      }
    }
    throw new IllegalStateException("Impossible de générer un code d'inventaire unique");
  }

  private static String truncate(String value, int max) {
    if (value == null) return null;
    return value.length() > max ? value.substring(0, max) : value;
  }

  // --- Côté administrateur -----------------------------------------------------------------------

  @Transactional(readOnly = true)
  public List<EnrollmentRequestResponse> list(Set<EnrollmentStatus> statuses) {
    var wanted =
        statuses == null || statuses.isEmpty()
            ? EnumSet.of(EnrollmentStatus.PENDING, EnrollmentStatus.APPROVED)
            : statuses;
    var visible = scope.restriction();
    return enrollments.findByStatusIn(wanted).stream()
        .filter(e -> visible.map(ids -> ids.contains(e.getSite().getId())).orElse(true))
        .map(AgentEnrollmentService::toResponse)
        .toList();
  }

  /**
   * Approuve des demandes en attente (ou refusées, si l'on revient sur un refus). L'équipement est
   * créé tout de suite, pour apparaître dans l'inventaire ; l'agent recevra son jeton à sa
   * prochaine demande.
   */
  @Transactional
  public EnrollmentDecisionResult approve(List<UUID> ids) {
    Instant now = clock.instant();
    String by = EnrollmentTokenService.currentUsername();
    int done = 0;
    List<String> skipped = new ArrayList<>();
    for (UUID id : new LinkedHashSet<>(ids)) {
      var found = visibleRequest(id);
      if (found.isEmpty()) {
        skipped.add(id + " : demande introuvable");
        continue;
      }
      var record = found.get();
      if (record.getStatus() != EnrollmentStatus.PENDING
          && record.getStatus() != EnrollmentStatus.REJECTED) {
        skipped.add(record.getHostname() + " : déjà " + label(record.getStatus()));
        continue;
      }
      if (record.getToken() == null) {
        skipped.add(record.getHostname() + " : jeton d'inscription supprimé");
        continue;
      }
      var equipment = equipmentFor(record, record.getToken());
      record.approve(equipment, by, now);
      enrollments.save(record);
      audit.record(
          "AGENT_ENROLLMENT_APPROVED",
          AUDIT_TARGET,
          record.getId(),
          "machine=" + record.getHostname() + " equipement=" + equipment.getInventoryCode());
      done++;
    }
    return new EnrollmentDecisionResult(done, skipped);
  }

  /** Refuse des demandes en attente, ou approuvées dont l'agent n'est pas encore venu. */
  @Transactional
  public EnrollmentDecisionResult reject(List<UUID> ids) {
    Instant now = clock.instant();
    String by = EnrollmentTokenService.currentUsername();
    int done = 0;
    List<String> skipped = new ArrayList<>();
    for (UUID id : new LinkedHashSet<>(ids)) {
      var found = visibleRequest(id);
      if (found.isEmpty()) {
        skipped.add(id + " : demande introuvable");
        continue;
      }
      var record = found.get();
      if (record.getStatus() != EnrollmentStatus.PENDING
          && record.getStatus() != EnrollmentStatus.APPROVED) {
        skipped.add(record.getHostname() + " : déjà " + label(record.getStatus()));
        continue;
      }
      record.reject(by, now);
      enrollments.save(record);
      audit.record(
          "AGENT_ENROLLMENT_REJECTED",
          AUDIT_TARGET,
          record.getId(),
          "machine=" + record.getHostname());
      done++;
    }
    return new EnrollmentDecisionResult(done, skipped);
  }

  /** Une demande hors périmètre est traitée comme introuvable (ADR 0018). */
  private Optional<AgentEnrollment> visibleRequest(UUID id) {
    return enrollments.findById(id).filter(e -> scope.allows(e.getSite().getId()));
  }

  private static String label(EnrollmentStatus status) {
    return switch (status) {
      case PENDING -> "en attente";
      case APPROVED -> "approuvée";
      case REJECTED -> "refusée";
      case ENROLLED -> "inscrite";
    };
  }

  private static EnrollmentRequestResponse toResponse(AgentEnrollment e) {
    var equipment = e.getEquipment();
    return new EnrollmentRequestResponse(
        e.getId(),
        e.getSite().getId(),
        e.getSite().getName(),
        e.getHostname(),
        e.getMachineId().substring(0, Math.min(12, e.getMachineId().length())),
        e.getIpAddresses() == null || e.getIpAddresses().isBlank()
            ? List.of()
            : List.of(e.getIpAddresses().split(",")),
        e.getSourceAddress(),
        e.getOsName(),
        e.getOsVersion(),
        e.getAgentVersion(),
        e.getStatus(),
        e.getToken() == null ? null : e.getToken().getName(),
        equipment == null ? null : equipment.getId(),
        equipment == null ? null : equipment.getName(),
        e.getRequestedAt(),
        e.getLastSeenAt(),
        e.getDecidedBy(),
        e.getDecidedAt(),
        e.getEnrolledAt());
  }
}

package sn.ept.transdic1.collection.service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import sn.ept.transdic1.audit.service.AuditService;
import sn.ept.transdic1.collection.dto.EnrollmentDtos.EnrollmentTokenCreated;
import sn.ept.transdic1.collection.dto.EnrollmentDtos.EnrollmentTokenRequest;
import sn.ept.transdic1.collection.dto.EnrollmentDtos.EnrollmentTokenResponse;
import sn.ept.transdic1.common.scope.SiteScope;
import sn.ept.transdic1.inventory.domain.EnrollmentToken;
import sn.ept.transdic1.inventory.domain.EquipmentType;
import sn.ept.transdic1.inventory.repository.EnrollmentTokenRepository;
import sn.ept.transdic1.inventory.repository.SiteRepository;

/**
 * Jetons d'inscription partagés (ADR 0054) : création, lecture et révocation par un administrateur.
 * Le jeton n'est rendu qu'à la création ; seule son empreinte est stockée.
 */
@Service
public class EnrollmentTokenService {
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final String AUDIT_TARGET = "ENROLLMENT_TOKEN";

  /** Un secret partagé par toute une vague de déploiement ne doit pas vivre indéfiniment. */
  static final Duration MAX_VALIDITY = Duration.ofDays(365);

  /** L'agent tourne sur un serveur ou une VM ; un routeur ou une borne n'en porte pas. */
  static final Set<EquipmentType> AGENT_TYPES =
      EnumSet.of(EquipmentType.SERVER, EquipmentType.VIRTUAL_MACHINE);

  private final EnrollmentTokenRepository tokens;
  private final SiteRepository sites;
  private final AuditService audit;
  private final SiteScope scope;
  private final Clock clock;

  public EnrollmentTokenService(
      EnrollmentTokenRepository tokens,
      SiteRepository sites,
      AuditService audit,
      SiteScope scope,
      Clock clock) {
    this.tokens = tokens;
    this.sites = sites;
    this.audit = audit;
    this.scope = scope;
    this.clock = clock;
  }

  @Transactional
  public EnrollmentTokenCreated create(EnrollmentTokenRequest request) {
    scope.requireAssignable(request.siteId());
    var site =
        sites
            .findById(request.siteId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Site inconnu"));
    Instant now = clock.instant();
    if (!request.expireLe().isAfter(now)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "La date d'expiration doit être dans le futur.");
    }
    if (request.expireLe().isAfter(now.plus(MAX_VALIDITY))) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Un jeton d'inscription vaut au plus 365 jours.");
    }
    if (!AGENT_TYPES.contains(request.typeEquipement())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "L'agent s'installe sur un serveur ou une machine virtuelle : type refusé.");
    }
    String ranges = normalizedRanges(request.plagesAutorisees());

    String token = newToken();
    var created =
        tokens.save(
            new EnrollmentToken(
                request.nom().trim(),
                site,
                AgentAuthService.hash(token),
                request.expireLe(),
                request.utilisationsMax(),
                ranges,
                request.mode(),
                request.typeEquipement(),
                currentUsername(),
                now));
    audit.record(
        "ENROLLMENT_TOKEN_CREATED",
        AUDIT_TARGET,
        created.getId(),
        "nom="
            + created.getName()
            + " site="
            + site.getCode()
            + " mode="
            + created.getApprovalMode()
            + " utilisationsMax="
            + created.getMaxUses()
            + " plages="
            + (ranges == null ? "toutes" : ranges));
    return new EnrollmentTokenCreated(
        created.getId(), created.getName(), token, created.getExpiresAt());
  }

  @Transactional(readOnly = true)
  public List<EnrollmentTokenResponse> list() {
    var visible = scope.restriction();
    Instant now = clock.instant();
    return tokens.findAllWithSite().stream()
        .filter(t -> visible.map(ids -> ids.contains(t.getSite().getId())).orElse(true))
        .map(t -> toResponse(t, now))
        .toList();
  }

  @Transactional
  public void revoke(UUID id) {
    var token =
        tokens
            .findById(id)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Jeton d'inscription introuvable"));
    scope.requireVisible(token.getSite().getId(), "Jeton d'inscription introuvable");
    if (token.isRevoked()) {
      return;
    }
    token.revoke(currentUsername(), clock.instant());
    audit.record("ENROLLMENT_TOKEN_REVOKED", AUDIT_TARGET, id, "nom=" + token.getName());
  }

  /** Vérifie chaque plage et les réécrit sous leur forme canonique ; null = toute adresse. */
  static String normalizedRanges(List<String> ranges) {
    if (ranges == null || ranges.isEmpty()) {
      return null;
    }
    try {
      List<String> canonical =
          ranges.stream().map(AddressRange::parse).map(AddressRange::toString).distinct().toList();
      return String.join(",", canonical);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }

  static EnrollmentTokenResponse toResponse(EnrollmentToken t, Instant now) {
    return new EnrollmentTokenResponse(
        t.getId(),
        t.getName(),
        t.getSite().getId(),
        t.getSite().getName(),
        t.getExpiresAt(),
        t.getMaxUses(),
        t.getUseCount(),
        t.getAllowedRanges() == null ? List.of() : List.of(t.getAllowedRanges().split(",")),
        t.getApprovalMode(),
        t.getEquipmentType(),
        state(t, now),
        t.getCreatedBy(),
        t.getCreatedAt(),
        t.getRevokedAt(),
        t.getLastUsedAt());
  }

  /** État affiché : le plus définitif l'emporte (révoqué, puis expiré, puis épuisé). */
  static String state(EnrollmentToken t, Instant now) {
    if (t.isRevoked()) return "REVOQUE";
    if (t.isExpired(now)) return "EXPIRE";
    if (t.isExhausted()) return "EPUISE";
    return "ACTIF";
  }

  static String currentUsername() {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || auth.getName() == null) {
      return null;
    }
    String name = auth.getName();
    return name.length() > 64 ? name.substring(0, 64) : name;
  }

  private static String newToken() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return EnrollmentAuthService.PREFIX
        + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}

package sn.ept.transdic1.collection.service;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import sn.ept.transdic1.alerting.repository.AlertRepository;
import sn.ept.transdic1.alerting.service.AlertEvaluationService;
import sn.ept.transdic1.audit.service.AuditService;
import sn.ept.transdic1.collection.dto.ProbeRequestDto;
import sn.ept.transdic1.collection.dto.ProbeResponse;
import sn.ept.transdic1.collection.dto.ProbeTestResult;
import sn.ept.transdic1.collection.probe.ProbeResult;
import sn.ept.transdic1.collection.probe.ProbeService;
import sn.ept.transdic1.collection.probe.ProbeTargetException;
import sn.ept.transdic1.collection.probe.ProbeTargetGuard;
import sn.ept.transdic1.common.scope.SiteScope;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.MonitoredService;
import sn.ept.transdic1.inventory.domain.ServiceType;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.MonitoredServiceRepository;
import sn.ept.transdic1.monitoring.repository.MetricRepository;

/**
 * Gestion des sondes sans agent d'un équipement : lecture selon le périmètre par site (ADR 0018),
 * écriture réservée aux administrateurs (contrôlée par le contrôleur), limites et cible validées à
 * la création comme à la modification, mutations tracées sans contenu sensible (ni chemin, ni
 * paramètres d'URL, ni texte attendu).
 */
@Service
public class ProbeAdminService {
  static final int MAX_ACTIVE_PER_EQUIPMENT = 50;
  private static final String AUDIT_TARGET = "PROBE";
  private static final String NOT_FOUND = "Sonde introuvable";
  private static final String EQUIPMENT_NOT_FOUND = "Équipement introuvable";
  private static final String DISABLED_COMMENT = "Sonde désactivée";
  private static final String TYPE_CHANGED_COMMENT = "Type de sonde modifié";

  private final EquipmentRepository equipments;
  private final MonitoredServiceRepository services;
  private final MetricRepository metrics;
  private final AlertRepository alerts;
  private final ProbeTargetGuard targets;
  private final ProbeService probes;
  private final AuditService audit;
  private final SiteScope scope;
  private final TransactionTemplate transaction;
  private final AlertEvaluationService alerting;
  private final IcmpReachabilityService icmp;
  private final Clock clock;

  public ProbeAdminService(
      EquipmentRepository equipments,
      MonitoredServiceRepository services,
      MetricRepository metrics,
      AlertRepository alerts,
      ProbeTargetGuard targets,
      ProbeService probes,
      AuditService audit,
      SiteScope scope,
      TransactionTemplate transaction,
      AlertEvaluationService alerting,
      IcmpReachabilityService icmp,
      Clock clock) {
    this.equipments = equipments;
    this.services = services;
    this.metrics = metrics;
    this.alerts = alerts;
    this.targets = targets;
    this.probes = probes;
    this.audit = audit;
    this.scope = scope;
    this.transaction = transaction;
    this.alerting = alerting;
    this.icmp = icmp;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public List<ProbeResponse> list(UUID equipmentId) {
    equipment(equipmentId);
    return services.findByEquipmentIdOrderByName(equipmentId).stream()
        .filter(s -> ProbeService.PROBE_TYPES.contains(s.getType()))
        .map(ProbeAdminService::toResponse)
        .toList();
  }

  @Transactional
  public ProbeResponse create(UUID equipmentId, ProbeRequestDto request) {
    Equipment equipment = equipment(equipmentId);
    validate(request);
    String name = request.name().trim();
    requireUniqueName(equipmentId, name, null);
    boolean active = request.active() == null || request.active();
    if (active) {
      requireActiveSlot(equipmentId);
    }
    var service = new MonitoredService(equipment, name, request.type());
    apply(service, request, active);
    services.saveAndFlush(service);
    audit.record("PROBE_CREATED", AUDIT_TARGET, service.getId(), details(equipmentId, service));
    return toResponse(service);
  }

  @Transactional
  public ProbeResponse update(UUID equipmentId, UUID id, ProbeRequestDto request) {
    MonitoredService service = find(equipmentId, id);
    validate(request);
    String name = request.name().trim();
    requireUniqueName(equipmentId, name, id);
    // Sans « active », l'état reste celui de la sonde (une sonde désactivée le reste).
    boolean wasActive = service.isActive();
    boolean wasHoldingDown = IcmpReachabilityService.holdsDown(service);
    boolean typeChanged = service.getType() != request.type();
    boolean targetChanged =
        !Objects.equals(service.getAccessPoint(), trimToNull(request.accessPoint()))
            || !Objects.equals(service.getPort(), request.port());
    boolean active = request.active() == null ? wasActive : request.active();
    if (active && !wasActive) {
      requireActiveSlot(equipmentId);
    }
    service.setName(name);
    service.setType(request.type());
    apply(service, request, active);
    if (typeChanged || targetChanged || (active && !wasActive)) {
      // Les derniers résultats portaient sur autre chose (autre type, autre cible, ou d'avant la
      // désactivation) : une sonde HTTP à 5 échecs devenue ICMP ne doit pas ouvrir une alerte de
      // collecte à son premier échec ICMP, ni un vieux compteur retenir l'équipement.
      resetState(service);
    } else if (wasActive && !active) {
      service.setConsecutiveFailures(0);
    }
    // Nouvelle cible ou nouvel intervalle : pris en compte dès le prochain passage.
    service.setNextRunAt(null);
    services.saveAndFlush(service);
    if (wasHoldingDown && !IcmpReachabilityService.holdsDown(service)) {
      icmp.release(service, clock.instant());
    }
    // Désactivée, ou d'un autre type : les alertes ouvertes ne correspondent plus à ce qui est
    // mesuré (un échec HTTP n'est pas un échec ICMP) et ne se refermeraient jamais seules.
    if ((wasActive && !active) || typeChanged) {
      resolveAlerts(id, typeChanged && active ? TYPE_CHANGED_COMMENT : DISABLED_COMMENT);
    }
    audit.record("PROBE_UPDATED", AUDIT_TARGET, id, details(equipmentId, service));
    return toResponse(service);
  }

  /**
   * Supprime la sonde. Si des mesures ou des alertes y renvoient déjà, elle est seulement
   * désactivée pour garder l'historique (comme un indicateur SNMP retiré) et ses alertes actives
   * sont résolues ; sur une sonde déjà désactivée, rien ne change. Une sonde ICMP qui retenait
   * l'équipement hors service le libère (voir {@link IcmpReachabilityService#release}).
   */
  @Transactional
  public void delete(UUID equipmentId, UUID id) {
    MonitoredService service = find(equipmentId, id);
    String details = details(equipmentId, service);
    if (metrics.existsByServiceId(id) || alerts.existsByServiceId(id)) {
      if (!service.isActive()) {
        return;
      }
      boolean wasHoldingDown = IcmpReachabilityService.holdsDown(service);
      service.setActive(false);
      service.setConsecutiveFailures(0);
      services.saveAndFlush(service);
      resolveAlerts(id, DISABLED_COMMENT);
      if (wasHoldingDown) {
        icmp.release(service, clock.instant());
      }
      audit.record("PROBE_DISABLED", AUDIT_TARGET, id, details);
      return;
    }
    boolean wasHoldingDown = IcmpReachabilityService.holdsDown(service);
    services.delete(service);
    services.flush();
    if (wasHoldingDown) {
      icmp.release(service, clock.instant());
    }
    audit.record("PROBE_DELETED", AUDIT_TARGET, id, details);
  }

  /**
   * Teste la sonde tout de suite : aucune mesure, aucune alerte, ni échéance ni dernier résultat
   * modifiés. Le service est lu dans une courte transaction, la sonde s'exécute hors transaction
   * pour ne pas garder de connexion à la base pendant l'attente réseau.
   */
  public ProbeTestResult test(UUID equipmentId, UUID id) {
    MonitoredService service =
        transaction.execute(
            status -> {
              MonitoredService found = find(equipmentId, id);
              // Charge l'équipement : l'adresse de gestion sert de cible par défaut.
              found.getEquipment().getManagementAddress();
              return found;
            });
    ProbeResult result = probes.runOnce(service);
    return new ProbeTestResult(
        result.up(), result.latencyMs(), result.tlsDaysLeft(), result.error());
  }

  // ------------------------------------------------------------------ outils

  private void validate(ProbeRequestDto request) {
    ServiceType type = request.type();
    if (!ProbeService.PROBE_TYPES.contains(type)) {
      throw badRequest("Type non pris en charge");
    }
    if (type == ServiceType.TCP_PORT && request.port() == null) {
      throw badRequest("Le port est obligatoire pour une sonde TCP");
    }
    if (type == ServiceType.DNS && isBlank(request.accessPoint())) {
      throw badRequest("Le nom à résoudre est obligatoire pour une sonde DNS");
    }
    boolean http = type == ServiceType.HTTP || type == ServiceType.HTTPS;
    // Pour une sonde DNS, le texte attendu est l'adresse que le nom doit rendre.
    if (!http && type != ServiceType.DNS && !isBlank(request.expectedText())) {
      throw badRequest("Le texte attendu ne s'applique qu'aux sondes HTTP, HTTPS et DNS");
    }
    if (!http && !isBlank(request.expectedStatus())) {
      throw badRequest("Le statut attendu ne s'applique qu'aux sondes HTTP et HTTPS");
    }
    if (!isBlank(request.expectedStatus())) {
      String[] bounds = request.expectedStatus().trim().split("-");
      if (bounds.length == 2 && Integer.parseInt(bounds[0]) > Integer.parseInt(bounds[1])) {
        throw badRequest("Plage de statut attendu inversée");
      }
    }
    if (!isBlank(request.accessPoint()) && hasUserInfo(request.accessPoint())) {
      throw badRequest("Identifiants dans l'URL non pris en charge");
    }
    if (!isBlank(request.accessPoint())) {
      requireMatchingScheme(type, request.accessPoint().trim());
    }
    String host = ProbeService.accessPointHost(type, request.accessPoint());
    if (host != null) {
      try {
        targets.resolve(host);
      } catch (ProbeTargetException e) {
        throw badRequest(e.getMessage());
      }
    }
  }

  private void apply(MonitoredService service, ProbeRequestDto request, boolean active) {
    service.setPort(request.port());
    service.setAccessPoint(trimToNull(request.accessPoint()));
    service.setIntervalSeconds(request.intervalSeconds() == null ? 60 : request.intervalSeconds());
    service.setTimeoutMs(request.timeoutMs() == null ? 5000 : request.timeoutMs());
    service.setExpectedStatus(trimToNull(request.expectedStatus()));
    service.setExpectedText(isBlank(request.expectedText()) ? null : request.expectedText());
    service.setActive(active);
  }

  /**
   * Le nom est unique par équipement pour tous les services (contrainte en base), y compris ceux
   * qui ne sont pas des sondes et n'apparaissent pas dans la liste : le message dit alors pourquoi
   * le nom est pris.
   */
  private void requireUniqueName(UUID equipmentId, String name, UUID self) {
    var conflict =
        services.findByEquipmentIdOrderByName(equipmentId).stream()
            .filter(s -> s.getName().equals(name) && !s.getId().equals(self))
            .findFirst();
    if (conflict.isEmpty()) {
      return;
    }
    MonitoredService existing = conflict.get();
    String message;
    if (!ProbeService.PROBE_TYPES.contains(existing.getType())) {
      message = "Un service non sondé porte déjà ce nom sur cet équipement : " + name;
    } else if (!existing.isActive()) {
      message = "Une sonde de ce nom existe déjà (désactivée) : réactivez-la ou renommez-la";
    } else {
      message = "Une sonde porte déjà ce nom sur cet équipement : " + name;
    }
    throw new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  /** Oublie les derniers résultats ; le prochain passage a lieu tout de suite. */
  private static void resetState(MonitoredService service) {
    service.setConsecutiveFailures(0);
    service.setLastUp(null);
    service.setLastError(null);
    service.setLastLatencyMs(null);
    service.setLastCheckedAt(null);
    service.setNextRunAt(null);
  }

  /** Sonde désactivée ou changée de type : ses alertes actives sont closes. */
  private void resolveAlerts(UUID serviceId, String comment) {
    alerting.resolveForService(serviceId, clock.instant(), comment);
  }

  /**
   * Le schéma éventuel doit correspondre au type : http:// pour HTTP, https:// pour HTTPS et
   * TLS_CERT, aucun pour TCP_PORT et ICMP (hôte seul). Sinon la sonde ne testerait pas ce que
   * l'adresse annonce (une sonde HTTP sur « https://… » parlerait en clair au port 443).
   */
  private static void requireMatchingScheme(ServiceType type, String accessPoint) {
    int separator = accessPoint.indexOf("://");
    if (separator < 0) {
      return;
    }
    String scheme = accessPoint.substring(0, separator).toLowerCase(Locale.ROOT);
    String expected =
        switch (type) {
          case HTTP -> "http";
          case HTTPS, TLS_CERT -> "https";
          default -> null;
        };
    if (expected == null) {
      throw badRequest("Pas de schéma (« " + scheme + ":// ») pour ce type : indiquez l'hôte seul");
    }
    if (!expected.equals(scheme)) {
      throw badRequest(
          "Le schéma « "
              + scheme
              + ":// » ne correspond pas au type : utilisez "
              + expected
              + "://");
    }
  }

  /** Vrai si l'adresse porte des identifiants (« http://utilisateur:secret@hote »). */
  private static boolean hasUserInfo(String accessPoint) {
    String value = accessPoint.trim();
    try {
      URI uri = new URI(value.contains("://") ? value : "//" + value);
      String authority = uri.getRawAuthority();
      return uri.getRawUserInfo() != null || (authority != null && authority.contains("@"));
    } catch (URISyntaxException e) {
      // Illisible : la garde refusera l'hôte, sauf si un « @ » trahit des identifiants.
      return value.contains("@");
    }
  }

  private void requireActiveSlot(UUID equipmentId) {
    if (services.countByEquipmentIdAndActiveTrue(equipmentId) >= MAX_ACTIVE_PER_EQUIPMENT) {
      throw badRequest(MAX_ACTIVE_PER_EQUIPMENT + " sondes actives au maximum par équipement");
    }
  }

  private Equipment equipment(UUID equipmentId) {
    Equipment equipment =
        equipments
            .findById(equipmentId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, EQUIPMENT_NOT_FOUND));
    scope.requireVisible(
        equipment.getSite() == null ? null : equipment.getSite().getId(), EQUIPMENT_NOT_FOUND);
    return equipment;
  }

  private MonitoredService find(UUID equipmentId, UUID id) {
    equipment(equipmentId);
    return services
        .findById(id)
        .filter(s -> s.getEquipment().getId().equals(equipmentId))
        .filter(s -> ProbeService.PROBE_TYPES.contains(s.getType()))
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, NOT_FOUND));
  }

  /** Nom, type, port et hôte seulement : ni chemin, ni paramètres d'URL, ni texte attendu. */
  private static String details(UUID equipmentId, MonitoredService service) {
    String host = ProbeService.accessPointHost(service.getType(), service.getAccessPoint());
    return "equipement="
        + equipmentId
        + " nom="
        + service.getName()
        + " type="
        + service.getType()
        + " port="
        + (service.getPort() == null ? "-" : service.getPort())
        + " hote="
        + (host == null ? "adresse-de-gestion" : host)
        + " actif="
        + service.isActive();
  }

  private static ProbeResponse toResponse(MonitoredService s) {
    return new ProbeResponse(
        s.getId(),
        s.getName(),
        s.getType(),
        s.getPort(),
        s.getAccessPoint(),
        s.getIntervalSeconds(),
        s.getTimeoutMs(),
        s.getExpectedStatus(),
        s.getExpectedText(),
        s.isActive(),
        s.getLastCheckedAt(),
        s.getLastUp(),
        s.getLastLatencyMs(),
        s.getLastError(),
        s.getConsecutiveFailures());
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private static String trimToNull(String value) {
    return isBlank(value) ? null : value.trim();
  }

  private static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }
}

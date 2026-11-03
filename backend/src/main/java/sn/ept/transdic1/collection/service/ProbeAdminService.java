package sn.ept.transdic1.collection.service;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import sn.ept.transdic1.alerting.repository.AlertRepository;
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

  private final EquipmentRepository equipments;
  private final MonitoredServiceRepository services;
  private final MetricRepository metrics;
  private final AlertRepository alerts;
  private final ProbeTargetGuard targets;
  private final ProbeService probes;
  private final AuditService audit;
  private final SiteScope scope;
  private final TransactionTemplate transaction;

  public ProbeAdminService(
      EquipmentRepository equipments,
      MonitoredServiceRepository services,
      MetricRepository metrics,
      AlertRepository alerts,
      ProbeTargetGuard targets,
      ProbeService probes,
      AuditService audit,
      SiteScope scope,
      TransactionTemplate transaction) {
    this.equipments = equipments;
    this.services = services;
    this.metrics = metrics;
    this.alerts = alerts;
    this.targets = targets;
    this.probes = probes;
    this.audit = audit;
    this.scope = scope;
    this.transaction = transaction;
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
    boolean active = request.active() == null || request.active();
    if (active && !service.isActive()) {
      requireActiveSlot(equipmentId);
    }
    service.setName(name);
    service.setType(request.type());
    apply(service, request, active);
    // Nouvelle cible ou nouvel intervalle : pris en compte dès le prochain passage.
    service.setNextRunAt(null);
    services.saveAndFlush(service);
    audit.record("PROBE_UPDATED", AUDIT_TARGET, id, details(equipmentId, service));
    return toResponse(service);
  }

  /**
   * Supprime la sonde. Si des mesures ou des alertes y renvoient déjà, elle est seulement
   * désactivée pour garder l'historique (comme un indicateur SNMP retiré).
   */
  @Transactional
  public void delete(UUID equipmentId, UUID id) {
    MonitoredService service = find(equipmentId, id);
    String details = details(equipmentId, service);
    if (metrics.existsByServiceId(id) || alerts.existsByServiceId(id)) {
      service.setActive(false);
      services.save(service);
      audit.record("PROBE_DISABLED", AUDIT_TARGET, id, details);
      return;
    }
    services.delete(service);
    services.flush();
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
    boolean http = type == ServiceType.HTTP || type == ServiceType.HTTPS;
    if (!http && !isBlank(request.expectedText())) {
      throw badRequest("Le texte attendu ne s'applique qu'aux sondes HTTP et HTTPS");
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

  private void requireUniqueName(UUID equipmentId, String name, UUID self) {
    boolean taken =
        services.findByEquipmentIdOrderByName(equipmentId).stream()
            .anyMatch(s -> s.getName().equals(name) && !s.getId().equals(self));
    if (taken) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Une sonde porte déjà ce nom sur cet équipement : " + name);
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

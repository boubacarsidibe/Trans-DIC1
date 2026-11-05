package sn.ept.transdic1.collection.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import sn.ept.transdic1.alerting.domain.AlertEventType;
import sn.ept.transdic1.alerting.service.AlertChange;
import sn.ept.transdic1.alerting.service.AlertEvaluationService;
import sn.ept.transdic1.alerting.service.CollectionAlertService;
import sn.ept.transdic1.collection.dto.IngestionResult;
import sn.ept.transdic1.collection.dto.MeasureInput;
import sn.ept.transdic1.collection.probe.ProbeResult;
import sn.ept.transdic1.inventory.domain.AgentStatus;
import sn.ept.transdic1.inventory.domain.CollectionMode;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.MonitoredService;
import sn.ept.transdic1.inventory.domain.ServiceType;
import sn.ept.transdic1.inventory.repository.CollectionAgentRepository;
import sn.ept.transdic1.inventory.repository.CollectionConfigurationRepository;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.MonitoredServiceRepository;
import sn.ept.transdic1.monitoring.domain.MeasurementQuality;
import sn.ept.transdic1.monitoring.domain.Metric;
import sn.ept.transdic1.monitoring.domain.MetricIngestedEvent;
import sn.ept.transdic1.monitoring.repository.MetricRepository;

/**
 * Point d'entrée commun des mesures, quelle que soit la source (agent, SNMP, sondes) : valide,
 * stocke, puis fait évaluer les alertes. Les mesures de mauvaise qualité sont conservées mais
 * n'ouvrent ni ne ferment aucune alerte.
 */
@Service
public class MeasureIngestionService {
  static final int MAX_BATCH = 500;

  /** Échecs ICMP consécutifs après lesquels un équipement sans autre source passe hors service. */
  static final int ICMP_FAILURES_BEFORE_DOWN = 3;

  /** Tolérance sur l'horloge d'une source : au-delà, la mesure est suspecte. */
  private static final Duration FUTURE_TOLERANCE = Duration.ofMinutes(5);

  private static final int LAST_ERROR_MAX = 200;

  private final EquipmentRepository equipments;
  private final MonitoredServiceRepository services;
  private final CollectionAgentRepository agents;
  private final CollectionConfigurationRepository configurations;
  private final MetricRepository metrics;
  private final AlertEvaluationService evaluation;
  private final CollectionAlertService collectionAlerts;
  private final Clock clock;
  private final ApplicationEventPublisher publisher;

  public MeasureIngestionService(
      EquipmentRepository equipments,
      MonitoredServiceRepository services,
      CollectionAgentRepository agents,
      CollectionConfigurationRepository configurations,
      MetricRepository metrics,
      AlertEvaluationService evaluation,
      CollectionAlertService collectionAlerts,
      Clock clock,
      ApplicationEventPublisher publisher) {
    this.publisher = publisher;
    this.equipments = equipments;
    this.services = services;
    this.agents = agents;
    this.configurations = configurations;
    this.metrics = metrics;
    this.evaluation = evaluation;
    this.collectionAlerts = collectionAlerts;
    this.clock = clock;
  }

  @Transactional
  public IngestionResult ingest(UUID equipmentId, List<MeasureInput> measures) {
    if (measures == null || measures.isEmpty() || measures.size() > MAX_BATCH) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Entre 1 et " + MAX_BATCH + " mesures par envoi");
    }
    var equipment =
        equipments
            .findById(equipmentId)
            .filter(e -> e.isMonitoringEnabled())
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Équipement inconnu ou non supervisé"));
    var limit = clock.instant().plus(FUTURE_TOLERANCE);
    for (MeasureInput measure : measures) {
      if (measure.collectedAt().isAfter(limit)) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "Horodatage dans le futur pour " + measure.key());
      }
    }

    var tally = new Tally();
    // Dans l'ordre du temps : la confirmation d'un dépassement dépend de l'historique.
    var ordered =
        measures.stream().sorted(Comparator.comparing(MeasureInput::collectedAt)).toList();
    for (MeasureInput measure : ordered) {
      store(
          equipment,
          null,
          measure.key(),
          measure.value(),
          measure.unit(),
          parseQuality(measure.quality()),
          measure.collectedAt(),
          tally);
    }
    // Une mesure exploitable prouve que la source répond : l'équipement repasse en service et
    // l'alerte de collecte éventuelle se résout.
    if (tally.evaluated > 0) {
      equipment.markReachable();
      if (collectionAlerts.reportRecovery(equipment, clock.instant()).isPresent()) {
        tally.resolved++;
      }
    }
    publisher.publishEvent(
        new MetricIngestedEvent(equipmentId, tally.stored, tally.evaluated, clock.instant()));
    return tally.result();
  }

  /**
   * Résultat d'une sonde : stocke {@code probe.up}, {@code probe.latency} et {@code
   * probe.tls.days_left} (ces deux dernières si présentes) avec le service, évalue les règles et
   * met à jour le dernier état du service. Une sonde ne prouve pas que la source de collecte répond
   * : ni le statut de l'équipement ni l'alerte de collecte ne bougent, sauf pour une sonde ICMP sur
   * un équipement sans agent actif ni collecte SNMP active, qui pilote alors le statut et l'alerte
   * de collecte (ouverte au 3e échec consécutif, résolue au premier succès). Un résultat
   * indéterminé ne stocke aucune mesure et ne change pas le compte des échecs consécutifs.
   */
  @Transactional
  public IngestionResult ingestProbe(UUID serviceId, ProbeResult result, Instant at) {
    var service =
        services
            .findById(serviceId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Service inconnu"));
    var equipment = service.getEquipment();
    if (!equipment.isMonitoringEnabled()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Équipement non supervisé");
    }
    if (at.isAfter(clock.instant().plus(FUTURE_TOLERANCE))) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Horodatage dans le futur");
    }

    var tally = new Tally();
    if (result.indeterminate()) {
      // La sonde n'a pas pu conclure : ni mesure trompeuse à 0, ni échec de plus au compteur.
      service.setLastCheckedAt(at);
      service.setLastUp(null);
      service.setLastLatencyMs(null);
      service.setLastError(truncate(result.error()));
      publisher.publishEvent(new MetricIngestedEvent(equipment.getId(), 0, 0, clock.instant()));
      return tally.result();
    }
    store(equipment, service, "probe.up", percent(result.up()), "%", at, tally);
    if (result.latencyMs() != null) {
      store(
          equipment,
          service,
          "probe.latency",
          BigDecimal.valueOf(result.latencyMs()),
          "ms",
          at,
          tally);
    }
    if (result.tlsDaysLeft() != null) {
      store(
          equipment,
          service,
          "probe.tls.days_left",
          BigDecimal.valueOf(result.tlsDaysLeft()),
          "j",
          at,
          tally);
    }

    service.setLastCheckedAt(at);
    service.setLastUp(result.up());
    service.setLastLatencyMs(result.latencyMs());
    service.setLastError(truncate(result.error()));
    service.setConsecutiveFailures(result.up() ? 0 : service.getConsecutiveFailures() + 1);

    if (service.getType() == ServiceType.ICMP && icmpDrivesStatus(equipment)) {
      // Même chemin qu'un agent muet : alerte de collecte (SLA, topologie, notifications, pas de
      // notification en maintenance). markReachable et markUnreachable, appelé par reportFailure,
      // laissent un équipement en maintenance inchangé ; les deux appels sont idempotents.
      if (result.up()) {
        equipment.markReachable();
        if (collectionAlerts.reportRecovery(equipment, at).isPresent()) {
          tally.resolved++;
        }
      } else if (service.getConsecutiveFailures() >= ICMP_FAILURES_BEFORE_DOWN) {
        if (collectionAlerts
            .reportFailure(
                equipment,
                "Équipement injoignable : "
                    + service.getConsecutiveFailures()
                    + " échecs consécutifs de la sonde ICMP « "
                    + service.getName()
                    + " »",
                at)
            .isPresent()) {
          tally.opened++;
        }
      }
    }
    publisher.publishEvent(
        new MetricIngestedEvent(equipment.getId(), tally.stored, tally.evaluated, clock.instant()));
    return tally.result();
  }

  /** Sans agent actif ni collecte SNMP active, seule la sonde ICMP renseigne la joignabilité. */
  private boolean icmpDrivesStatus(Equipment equipment) {
    if (agents.findByEquipmentIdAndStatus(equipment.getId(), AgentStatus.ACTIVE).isPresent()) {
      return false;
    }
    return configurations.findByEquipmentId(equipment.getId()).stream()
        .noneMatch(c -> c.isActive() && c.getMode() == CollectionMode.SNMP);
  }

  private void store(
      Equipment equipment,
      MonitoredService service,
      String key,
      BigDecimal value,
      String unit,
      Instant at,
      Tally tally) {
    store(equipment, service, key, value, unit, MeasurementQuality.GOOD, at, tally);
  }

  /** Stocke une mesure puis, si elle est exploitable, la fait évaluer par les règles. */
  private void store(
      Equipment equipment,
      MonitoredService service,
      String key,
      BigDecimal value,
      String unit,
      MeasurementQuality quality,
      Instant at,
      Tally tally) {
    var metric = new Metric(equipment, key, value, unit, quality, at);
    metric.setService(service);
    metrics.save(metric);
    tally.stored++;
    if (quality == MeasurementQuality.BAD) {
      return;
    }
    metrics.flush();
    tally.evaluated++;
    for (AlertChange change : evaluation.evaluate(equipment, service, key, value, at)) {
      if (change.type() == AlertEventType.OPENED) {
        tally.opened++;
      } else if (change.type() == AlertEventType.RESOLVED) {
        tally.resolved++;
      }
    }
  }

  private static BigDecimal percent(boolean up) {
    return BigDecimal.valueOf(up ? 100 : 0);
  }

  private static String truncate(String error) {
    return error == null || error.length() <= LAST_ERROR_MAX
        ? error
        : error.substring(0, LAST_ERROR_MAX);
  }

  private static MeasurementQuality parseQuality(String quality) {
    if (quality == null || quality.isBlank()) {
      return MeasurementQuality.GOOD;
    }
    try {
      return MeasurementQuality.valueOf(quality.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Qualité inconnue : " + quality);
    }
  }

  /** Compteurs d'un envoi. */
  private static final class Tally {
    int stored;
    int evaluated;
    int opened;
    int resolved;

    IngestionResult result() {
      return new IngestionResult(stored, evaluated, opened, resolved);
    }
  }
}

package sn.ept.transdic1.collection.service;

import java.time.Clock;
import java.time.Duration;
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
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.monitoring.domain.MeasurementQuality;
import sn.ept.transdic1.monitoring.domain.Metric;
import sn.ept.transdic1.monitoring.domain.MetricIngestedEvent;
import sn.ept.transdic1.monitoring.repository.MetricRepository;

/**
 * Point d'entrée commun des mesures, quelle que soit la source (agent, SNMP) : valide, stocke, puis
 * fait évaluer les alertes. Les mesures de mauvaise qualité sont conservées mais n'ouvrent ni ne
 * ferment aucune alerte.
 */
@Service
public class MeasureIngestionService {
  static final int MAX_BATCH = 500;

  /** Tolérance sur l'horloge d'une source : au-delà, la mesure est suspecte. */
  private static final Duration FUTURE_TOLERANCE = Duration.ofMinutes(5);

  private final EquipmentRepository equipments;
  private final MetricRepository metrics;
  private final AlertEvaluationService evaluation;
  private final CollectionAlertService collectionAlerts;
  private final Clock clock;
  private final ApplicationEventPublisher publisher;

  public MeasureIngestionService(
      EquipmentRepository equipments,
      MetricRepository metrics,
      AlertEvaluationService evaluation,
      CollectionAlertService collectionAlerts,
      Clock clock,
      ApplicationEventPublisher publisher) {
    this.publisher = publisher;
    this.equipments = equipments;
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

    int evaluated = 0;
    int opened = 0;
    int resolved = 0;
    // Dans l'ordre du temps : la confirmation d'un dépassement dépend de l'historique.
    var ordered =
        measures.stream().sorted(Comparator.comparing(MeasureInput::collectedAt)).toList();
    for (MeasureInput measure : ordered) {
      MeasurementQuality quality = parseQuality(measure.quality());
      metrics.save(
          new Metric(
              equipment,
              measure.key(),
              measure.value(),
              measure.unit(),
              quality,
              measure.collectedAt()));
      if (quality == MeasurementQuality.BAD) {
        continue;
      }
      metrics.flush();
      evaluated++;
      for (AlertChange change :
          evaluation.evaluate(equipment, measure.key(), measure.value(), measure.collectedAt())) {
        if (change.type() == AlertEventType.OPENED) {
          opened++;
        } else if (change.type() == AlertEventType.RESOLVED) {
          resolved++;
        }
      }
    }
    // Une mesure exploitable prouve que la source répond : l'équipement repasse en service et
    // l'alerte de collecte éventuelle se résout.
    if (evaluated > 0) {
      equipment.markReachable();
      if (collectionAlerts.reportRecovery(equipment, clock.instant()).isPresent()) {
        resolved++;
      }
    }
    publisher.publishEvent(
        new MetricIngestedEvent(equipmentId, ordered.size(), evaluated, clock.instant()));
    return new IngestionResult(ordered.size(), evaluated, opened, resolved);
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
}

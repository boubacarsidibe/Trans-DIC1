package sn.ept.transdic1.alerting.service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.alerting.domain.Alert;
import sn.ept.transdic1.alerting.domain.AlertActivityEvent;
import sn.ept.transdic1.alerting.domain.AlertChangedEvent;
import sn.ept.transdic1.alerting.domain.AlertEvent;
import sn.ept.transdic1.alerting.domain.AlertEventType;
import sn.ept.transdic1.alerting.domain.AlertState;
import sn.ept.transdic1.alerting.domain.AlertType;
import sn.ept.transdic1.alerting.domain.ResolutionType;
import sn.ept.transdic1.alerting.domain.Severity;
import sn.ept.transdic1.alerting.repository.AlertEventRepository;
import sn.ept.transdic1.alerting.repository.AlertRepository;
import sn.ept.transdic1.common.scheduling.JobGuard;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;

/**
 * Détection d'anomalies sur les indicateurs. Pour chaque équipement supervisé et chaque indicateur
 * mesuré dans la dernière heure, la valeur récente (moyenne des 15 dernières minutes) est comparée
 * à la normale du même créneau : moyennes horaires des 4 dernières semaines au même jour et à la
 * même heure (heure de Dakar), ou, faute de données, à la même heure tous les jours. Un écart d'au
 * moins {@code sensitivity} écarts-types ouvre une alerte {@code ANOMALY} ; elle se résout seule
 * quand la valeur revient sous 70 % de ce seuil.
 */
@Service
public class AnomalyDetectionService {
  private static final Logger LOG = LoggerFactory.getLogger(AnomalyDetectionService.class);
  private static final Set<AlertState> ACTIVE = Set.of(AlertState.OPEN, AlertState.IN_PROGRESS);
  private static final ZoneId ZONE = ZoneId.of("Africa/Dakar");

  private final JdbcTemplate jdbc;
  private final AlertRepository alerts;
  private final AlertEventRepository events;
  private final EquipmentRepository equipments;
  private final ApplicationEventPublisher publisher;
  private final JobGuard guard;
  private final Clock clock;
  private final boolean enabled;
  private double sensitivity;

  public AnomalyDetectionService(
      JdbcTemplate jdbc,
      AlertRepository alerts,
      AlertEventRepository events,
      EquipmentRepository equipments,
      ApplicationEventPublisher publisher,
      JobGuard guard,
      Clock clock,
      @Value("${alerting.anomaly.enabled:true}") boolean enabled,
      @Value("${alerting.anomaly.sensitivity:3.5}") double sensitivity) {
    this.jdbc = jdbc;
    this.alerts = alerts;
    this.events = events;
    this.equipments = equipments;
    this.publisher = publisher;
    this.guard = guard;
    this.clock = clock;
    this.enabled = enabled;
    this.sensitivity = sensitivity;
  }

  public double getSensitivity() {
    return sensitivity;
  }

  /** Sensibilité en écarts-types (plus haut = moins d'alertes). Bornée entre 1,5 et 10. */
  public void setSensitivity(double sensitivity) {
    this.sensitivity = Math.max(1.5, Math.min(10, sensitivity));
  }

  @Scheduled(cron = "${alerting.anomaly.cron:0 */10 * * * *}", zone = "UTC")
  public void scheduledScan() {
    if (enabled) {
      guard.run("alerting.anomaly", Duration.ofMinutes(9), this::scan);
    }
  }

  /** Un passage complet ; retourne le nombre d'alertes ouvertes. */
  @Transactional
  public int scan() {
    Instant now = clock.instant();
    var recent =
        jdbc.query(
            "SELECT m.equipment_id, m.metric_key, avg(m.metric_value), max(m.unit) FROM metric m"
                + " JOIN equipment e ON e.id = m.equipment_id AND e.monitoring_enabled"
                + " WHERE m.collected_at >= ? AND m.quality <> 'BAD'"
                + " GROUP BY m.equipment_id, m.metric_key",
            (rs, i) ->
                new Recent(
                    rs.getObject(1, UUID.class),
                    rs.getString(2),
                    rs.getBigDecimal(3).doubleValue(),
                    rs.getString(4)),
            Timestamp.from(now.minus(Duration.ofMinutes(15))));
    int opened = 0;
    for (Recent value : recent) {
      if (evaluate(value, now)) {
        opened++;
      }
    }
    return opened;
  }

  private record Recent(UUID equipmentId, String key, double value, String unit) {}

  /** Valeurs minimales pour juger sur le créneau « type de jour + heure ». */
  static final int MIN_SLOT_SAMPLES = 6;

  /**
   * Normale du créneau : moyennes horaires des 28 derniers jours à la même heure (heure de Dakar)
   * et le même type de jour (ouvré ou week-end). Faute de données, même heure tous jours confondus.
   */
  Optional<AnomalyDetector.Baseline> baseline(UUID equipmentId, String key, Instant at) {
    ZonedDateTime local = at.atZone(ZONE);
    boolean weekend = local.getDayOfWeek().getValue() >= 6;
    Timestamp since = Timestamp.from(at.minus(Duration.ofDays(28)));
    Timestamp until = Timestamp.from(at.minus(Duration.ofHours(1)));
    String base =
        "SELECT avg_value FROM metric_hourly WHERE equipment_id = ? AND metric_key = ?"
            + " AND hour_start >= ? AND hour_start < ?"
            + " AND extract(hour FROM hour_start AT TIME ZONE 'Africa/Dakar') = ?";
    List<Double> sameSlot =
        jdbc.query(
            base + " AND (extract(isodow FROM hour_start AT TIME ZONE 'Africa/Dakar') >= 6) = ?",
            (rs, i) -> rs.getBigDecimal(1).doubleValue(),
            equipmentId,
            key,
            since,
            until,
            local.getHour(),
            weekend);
    if (sameSlot.size() >= MIN_SLOT_SAMPLES) {
      return AnomalyDetector.baseline(sameSlot);
    }
    List<Double> sameHour =
        jdbc.query(
            base,
            (rs, i) -> rs.getBigDecimal(1).doubleValue(),
            equipmentId,
            key,
            since,
            until,
            local.getHour());
    return AnomalyDetector.baseline(sameHour);
  }

  private boolean evaluate(Recent recent, Instant now) {
    var baseline = baseline(recent.equipmentId(), recent.key(), now);
    if (baseline.isEmpty()) {
      return false;
    }
    String prefix = "anomaly:" + recent.equipmentId() + ":" + recent.key() + ":";
    Optional<Alert> active = alerts.findFirstByOccurrenceKeyStartingWithAndStateIn(prefix, ACTIVE);
    var b = baseline.get();
    if (active.isPresent()) {
      if (AnomalyDetector.isBackToNormal(b, recent.value(), sensitivity)) {
        resolve(active.get(), now);
      }
      return false;
    }
    if (!AnomalyDetector.isAnomalous(b, recent.value(), sensitivity)) {
      return false;
    }
    var equipment = equipments.findById(recent.equipmentId()).orElse(null);
    if (equipment == null) {
      return false;
    }
    String message = explain(recent, b, now);
    var alert =
        new Alert(
            equipment,
            prefix + now.toEpochMilli(),
            AlertType.ANOMALY,
            AlertState.OPEN,
            Severity.WARNING,
            message,
            now);
    var event = new AlertEvent(alert, AlertEventType.OPENED, now);
    event.setNewState(AlertState.OPEN);
    alerts.save(alert);
    events.save(event);
    publisher.publishEvent(new AlertChangedEvent(alert.getId(), AlertEventType.OPENED));
    publisher.publishEvent(new AlertActivityEvent(alert.getId(), AlertEventType.OPENED));
    LOG.info("Anomalie ouverte sur {} ({})", recent.equipmentId(), recent.key());
    return true;
  }

  private void resolve(Alert alert, Instant now) {
    AlertState previous = alert.getState();
    alert.setState(AlertState.RESOLVED);
    alert.setResolvedAt(now);
    alert.setResolutionType(ResolutionType.AUTOMATIC);
    var event = new AlertEvent(alert, AlertEventType.RESOLVED, now);
    event.setPreviousState(previous);
    event.setNewState(AlertState.RESOLVED);
    event.setComment("Retour au comportement habituel");
    alerts.save(alert);
    events.save(event);
    publisher.publishEvent(new AlertChangedEvent(alert.getId(), AlertEventType.RESOLVED));
    publisher.publishEvent(new AlertActivityEvent(alert.getId(), AlertEventType.RESOLVED));
  }

  /** Message lisible : valeur, normale du créneau et écart, pour que l'alerte s'explique seule. */
  static String explain(Recent recent, AnomalyDetector.Baseline b, Instant at) {
    ZonedDateTime local = at.atZone(ZONE);
    return "Comportement inhabituel : "
        + recent.key()
        + " = "
        + round(recent.value())
        + " "
        + recent.unit()
        + " alors que la normale "
        + (local.getDayOfWeek().getValue() >= 6 ? "le week-end" : "en semaine")
        + " vers "
        + local.getHour()
        + " h est "
        + round(b.mean())
        + " ± "
        + round(b.deviation())
        + " (écart de "
        + round(Math.abs(b.score(recent.value())))
        + " écarts-types)";
  }

  private static String round(double value) {
    return BigDecimal.valueOf(value).setScale(1, java.math.RoundingMode.HALF_UP).toPlainString();
  }
}

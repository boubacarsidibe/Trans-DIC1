package sn.ept.transdic1.alerting.service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import sn.ept.transdic1.inventory.domain.MonitoredService;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.MonitoredServiceRepository;

/**
 * Détection d'anomalies sur les indicateurs. Pour chaque équipement supervisé et chaque indicateur
 * mesuré dans la dernière heure, la valeur récente (moyenne des 15 dernières minutes) est comparée
 * à la normale du même créneau : moyennes horaires des 4 dernières semaines au même jour et à la
 * même heure (heure de Dakar), ou, faute de données, à la même heure tous les jours. Un écart d'au
 * moins {@code sensitivity} écarts-types ouvre une alerte {@code ANOMALY} ; elle se résout seule
 * quand la valeur revient sous 70 % de ce seuil. Les mesures de sondes sans agent (avec service) ne
 * sont pas examinées, sauf la latence : un service nettement plus lent que d'habitude ouvre une
 * alerte avant de tomber (voir {@link #scanProbeLatency}).
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
  private final MonitoredServiceRepository services;
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
      MonitoredServiceRepository services,
      ApplicationEventPublisher publisher,
      JobGuard guard,
      Clock clock,
      @Value("${alerting.anomaly.enabled:true}") boolean enabled,
      @Value("${alerting.anomaly.sensitivity:3.5}") double sensitivity) {
    this.jdbc = jdbc;
    this.alerts = alerts;
    this.events = events;
    this.equipments = equipments;
    this.services = services;
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
    // Les mesures de sondes (service renseigné) sont exclues : un seul échec d'une série toujours
    // à 100 % serait sinon une « anomalie », et plusieurs sondes d'un même équipement partagent la
    // même clé. Leurs pannes passent par les règles d'alerte et le statut piloté par ICMP.
    var recent =
        jdbc.query(
            "SELECT m.equipment_id, m.metric_key, avg(m.metric_value), max(m.unit) FROM metric m"
                + " JOIN equipment e ON e.id = m.equipment_id AND e.monitoring_enabled"
                + " WHERE m.collected_at >= ? AND m.quality <> 'BAD' AND m.service_id IS NULL"
                + " GROUP BY m.equipment_id, m.metric_key",
            (rs, i) ->
                new Recent(
                    rs.getObject(1, UUID.class),
                    rs.getString(2),
                    rs.getBigDecimal(3).doubleValue(),
                    rs.getString(4)),
            Timestamp.from(now.minus(Duration.ofMinutes(15))));
    int opened = 0;
    var tally = new HashMap<UUID, int[]>();
    for (Recent value : recent) {
      Outcome outcome = evaluate(value, now);
      if (outcome.judged()) {
        int[] counts = tally.computeIfAbsent(value.equipmentId(), id -> new int[2]);
        counts[0]++;
        if (outcome.anomalous()) {
          counts[1]++;
        }
      }
      if (outcome.opened()) {
        opened++;
      }
    }
    storeAnomalyRates(tally, now);
    return opened + scanProbeLatency(now);
  }

  /**
   * Part des indicateurs hors de leur normale, par équipement (M3) : le détecteur vient de juger
   * chaque indicateur, autant garder le compte. Le score de santé le lit sans tout recalculer. Une
   * seule ligne par équipement, remplacée à chaque passage.
   */
  private void storeAnomalyRates(Map<UUID, int[]> tally, Instant now) {
    for (var entry : tally.entrySet()) {
      jdbc.update(
          "INSERT INTO equipment_anomaly_rate(equipment_id, judged, anomalous, computed_at)"
              + " VALUES (?, ?, ?, ?) ON CONFLICT (equipment_id) DO UPDATE"
              + " SET judged = excluded.judged, anomalous = excluded.anomalous,"
              + " computed_at = excluded.computed_at",
          entry.getKey(),
          entry.getValue()[0],
          entry.getValue()[1],
          Timestamp.from(now));
    }
  }

  /**
   * Verdict sur un indicateur : jugé ou non (sans normale fiable, on ne juge pas), hors de sa
   * normale ou non, et alerte ouverte ou non (une alerte déjà active n'en rouvre pas une).
   */
  private record Outcome(boolean judged, boolean anomalous, boolean opened) {
    static final Outcome NOT_JUDGED = new Outcome(false, false, false);
    static final Outcome NORMAL = new Outcome(true, false, false);
    static final Outcome ANOMALOUS = new Outcome(true, true, false);
    static final Outcome OPENED = new Outcome(true, true, true);
  }

  /** Mesures de latence nécessaires dans les 15 dernières minutes : une seule ne prouve rien. */
  static final int MIN_LATENCY_SAMPLES = 3;

  /** Écart minimal en millisecondes : sous ce seuil, une sonde locale « bruite » sans gravité. */
  static final double LATENCY_FLOOR_MS = 5.0;

  /** Écart minimal en part de la latence habituelle. */
  static final double LATENCY_FLOOR_RATIO = 0.2;

  /**
   * Latence des sondes, service par service. Seul un ralentissement compte (un service plus rapide
   * n'est pas un problème). La normale vient des mesures brutes des 28 derniers jours, regroupées
   * par heure : les agrégats horaires ne distinguent pas les services d'un même équipement. Avec la
   * rétention par défaut des mesures brutes (30 jours), tout l'historique utile est disponible.
   */
  int scanProbeLatency(Instant now) {
    var recent =
        jdbc.query(
            "SELECT m.equipment_id, m.service_id, avg(m.metric_value), count(*) FROM metric m"
                + " JOIN equipment e ON e.id = m.equipment_id AND e.monitoring_enabled"
                + " JOIN monitored_service s ON s.id = m.service_id AND s.active"
                + " WHERE m.collected_at >= ? AND m.quality <> 'BAD'"
                + " AND m.metric_key = 'probe.latency'"
                + " GROUP BY m.equipment_id, m.service_id",
            (rs, i) ->
                new RecentLatency(
                    rs.getObject(1, UUID.class),
                    rs.getObject(2, UUID.class),
                    rs.getBigDecimal(3).doubleValue(),
                    rs.getInt(4)),
            Timestamp.from(now.minus(Duration.ofMinutes(15))));
    int opened = 0;
    for (RecentLatency value : recent) {
      if (value.samples() >= MIN_LATENCY_SAMPLES && evaluateLatency(value, now)) {
        opened++;
      }
    }
    return opened;
  }

  private record RecentLatency(UUID equipmentId, UUID serviceId, double value, int samples) {}

  Optional<AnomalyDetector.Baseline> latencyBaseline(UUID serviceId, Instant at) {
    ZonedDateTime local = at.atZone(ZONE);
    boolean weekend = local.getDayOfWeek().getValue() >= 6;
    Timestamp since = Timestamp.from(at.minus(Duration.ofDays(28)));
    Timestamp until = Timestamp.from(at.minus(Duration.ofHours(1)));
    String head =
        "SELECT avg(metric_value) FROM metric WHERE service_id = ? AND metric_key = 'probe.latency'"
            + " AND quality <> 'BAD' AND collected_at >= ? AND collected_at < ?"
            + " AND extract(hour FROM collected_at AT TIME ZONE 'Africa/Dakar') = ?";
    String tail = " GROUP BY date_trunc('hour', collected_at)";
    List<Double> sameSlot =
        jdbc.query(
            head
                + " AND (extract(isodow FROM collected_at AT TIME ZONE 'Africa/Dakar') >= 6) = ?"
                + tail,
            (rs, i) -> rs.getBigDecimal(1).doubleValue(),
            serviceId,
            since,
            until,
            local.getHour(),
            weekend);
    List<Double> history =
        sameSlot.size() >= MIN_SLOT_SAMPLES
            ? sameSlot
            : jdbc.query(
                head + tail,
                (rs, i) -> rs.getBigDecimal(1).doubleValue(),
                serviceId,
                since,
                until,
                local.getHour());
    // Plancher propre à la latence : quelques millisecondes de plus ne sont pas un incident.
    return AnomalyDetector.baseline(history)
        .map(
            b ->
                new AnomalyDetector.Baseline(
                    b.mean(),
                    b.deviation(),
                    Math.max(LATENCY_FLOOR_MS, b.mean() * LATENCY_FLOOR_RATIO),
                    b.samples(),
                    b.excluded()));
  }

  private boolean evaluateLatency(RecentLatency recent, Instant now) {
    var baseline = latencyBaseline(recent.serviceId(), now);
    if (baseline.isEmpty()) {
      return false;
    }
    var b = baseline.get();
    // Un seul sens : seul un service plus lent que d'habitude est signalé.
    double score = b.score(recent.value());
    String prefix =
        "anomaly:" + recent.equipmentId() + ":probe.latency:" + recent.serviceId() + "-";
    Optional<Alert> active = alerts.findFirstByOccurrenceKeyStartingWithAndStateIn(prefix, ACTIVE);
    if (active.isPresent()) {
      if (score < sensitivity * 0.7) {
        resolve(active.get(), now);
      }
      return false;
    }
    if (score < sensitivity) {
      return false;
    }
    MonitoredService service = services.findById(recent.serviceId()).orElse(null);
    if (service == null) {
      return false;
    }
    ZonedDateTime local = now.atZone(ZONE);
    String message =
        "Service plus lent que d'habitude : "
            + service.getName()
            + " répond en "
            + round(recent.value())
            + " ms alors que la normale "
            + (local.getDayOfWeek().getValue() >= 6 ? "le week-end" : "en semaine")
            + " vers "
            + local.getHour()
            + " h est "
            + round(b.mean())
            + " ± "
            + round(b.deviation())
            + " ms";
    var alert =
        new Alert(
            service.getEquipment(),
            prefix + now.toEpochMilli(),
            AlertType.ANOMALY,
            AlertState.OPEN,
            Severity.WARNING,
            message,
            now);
    alert.setService(service);
    var event = new AlertEvent(alert, AlertEventType.OPENED, now);
    event.setNewState(AlertState.OPEN);
    alerts.save(alert);
    events.save(event);
    publisher.publishEvent(new AlertChangedEvent(alert.getId(), AlertEventType.OPENED));
    publisher.publishEvent(new AlertActivityEvent(alert.getId(), AlertEventType.OPENED));
    LOG.info("Latence inhabituelle sur le service {}", recent.serviceId());
    return true;
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

  private Outcome evaluate(Recent recent, Instant now) {
    var baseline = baseline(recent.equipmentId(), recent.key(), now);
    if (baseline.isEmpty()) {
      return Outcome.NOT_JUDGED;
    }
    String prefix = "anomaly:" + recent.equipmentId() + ":" + recent.key() + ":";
    Optional<Alert> active = alerts.findFirstByOccurrenceKeyStartingWithAndStateIn(prefix, ACTIVE);
    var b = baseline.get();
    if (active.isPresent()) {
      if (AnomalyDetector.isBackToNormal(b, recent.value(), sensitivity)) {
        resolve(active.get(), now);
        return Outcome.NORMAL;
      }
      return Outcome.ANOMALOUS;
    }
    if (!AnomalyDetector.isAnomalous(b, recent.value(), sensitivity)) {
      return Outcome.NORMAL;
    }
    var equipment = equipments.findById(recent.equipmentId()).orElse(null);
    if (equipment == null) {
      return Outcome.ANOMALOUS;
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
    return Outcome.OPENED;
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
        + " écarts-types)"
        + (b.excluded() > 0
            ? " ; relevés passés inhabituels écartés de la normale : " + b.excluded()
            : "");
  }

  private static String round(double value) {
    return BigDecimal.valueOf(value).setScale(1, java.math.RoundingMode.HALF_UP).toPlainString();
  }
}

package sn.ept.transdic1.alerting.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import sn.ept.transdic1.alerting.domain.Alert;
import sn.ept.transdic1.alerting.domain.AlertActivityEvent;
import sn.ept.transdic1.alerting.domain.AlertChangedEvent;
import sn.ept.transdic1.alerting.domain.AlertEvent;
import sn.ept.transdic1.alerting.domain.AlertEventType;
import sn.ept.transdic1.alerting.domain.AlertState;
import sn.ept.transdic1.alerting.domain.AlertType;
import sn.ept.transdic1.alerting.domain.ForecastStatus;
import sn.ept.transdic1.alerting.domain.MaintenanceState;
import sn.ept.transdic1.alerting.domain.ResolutionType;
import sn.ept.transdic1.alerting.domain.Severity;
import sn.ept.transdic1.alerting.dto.CapacityForecastResponse;
import sn.ept.transdic1.alerting.repository.AlertEventRepository;
import sn.ept.transdic1.alerting.repository.AlertRepository;
import sn.ept.transdic1.alerting.repository.MaintenanceRepository;
import sn.ept.transdic1.alerting.service.CapacityForecast.Point;
import sn.ept.transdic1.alerting.service.CapacityForecast.Result;
import sn.ept.transdic1.alerting.service.CapacityForecast.Settings;
import sn.ept.transdic1.common.scheduling.JobGuard;
import sn.ept.transdic1.common.scope.SiteScope;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;

/**
 * Prévision de saturation (ADR 0020) : pour chaque équipement supervisé et chaque indicateur suivi
 * (disque et mémoire en pourcentage par défaut), une droite des moindres carrés sur les moyennes
 * horaires des derniers jours dit dans combien de jours la limite sera atteinte.
 *
 * <p>Toutes les heures, une alerte {@code FORECAST} s'ouvre si la saturation est prévue dans
 * l'horizon avec une confiance suffisante ; elle se résout seule quand la tendance disparaît ou que
 * la saturation recule au-delà de 1,25 fois l'horizon (hystérésis). Les mesures de sondes sans
 * agent (avec service) ne sont pas prises en compte, comme pour les anomalies.
 */
@Service
public class CapacityForecastService {
  private static final Logger LOG = LoggerFactory.getLogger(CapacityForecastService.class);
  private static final Set<AlertState> ACTIVE = Set.of(AlertState.OPEN, AlertState.IN_PROGRESS);
  private static final Set<MaintenanceState> MAINTENANCE_STATES =
      Set.of(MaintenanceState.PLANNED, MaintenanceState.IN_PROGRESS);

  /** Saturation prévue dans 3 jours ou moins : alerte critique. */
  static final double CRITICAL_DAYS = 3.0;

  /** Résolution quand la saturation recule au-delà de l'horizon multiplié par ce facteur. */
  static final double RESOLVE_FACTOR = 1.25;

  /** Délai minimal entre deux mises à jour du message d'une alerte active. */
  static final Duration MESSAGE_REFRESH = Duration.ofDays(1);

  /** Un indicateur est examiné par l'alerte s'il a été mesuré dans ce délai. */
  private static final Duration RECENT = Duration.ofDays(1);

  private final JdbcTemplate jdbc;
  private final AlertRepository alerts;
  private final AlertEventRepository events;
  private final EquipmentRepository equipments;
  private final MaintenanceRepository maintenances;
  private final ApplicationEventPublisher publisher;
  private final JobGuard guard;
  private final Clock clock;
  private final SiteScope scope;
  private final TransactionTemplate transaction;
  private final boolean enabled;
  private final int windowDays;
  private final Settings settings;
  private final List<String> families;
  private final double minR2;
  private final double horizonDays;

  public CapacityForecastService(
      JdbcTemplate jdbc,
      AlertRepository alerts,
      AlertEventRepository events,
      EquipmentRepository equipments,
      MaintenanceRepository maintenances,
      ApplicationEventPublisher publisher,
      JobGuard guard,
      Clock clock,
      SiteScope scope,
      PlatformTransactionManager transactionManager,
      @Value("${forecast.enabled:true}") boolean enabled,
      @Value("${forecast.window-days:14}") int windowDays,
      @Value("${forecast.min-span-days:3}") int minSpanDays,
      @Value("${forecast.min-points:24}") int minPoints,
      @Value("${forecast.keys:disk.usage,memory.usage}") String keys,
      @Value("${forecast.limit:100}") double limit,
      @Value("${forecast.min-r2:0.6}") double minR2,
      @Value("${forecast.horizon-days:14}") double horizonDays) {
    this.jdbc = jdbc;
    this.alerts = alerts;
    this.events = events;
    this.equipments = equipments;
    this.maintenances = maintenances;
    this.publisher = publisher;
    this.guard = guard;
    this.clock = clock;
    this.scope = scope;
    this.transaction = new TransactionTemplate(transactionManager);
    this.enabled = enabled;
    this.windowDays = Math.max(1, windowDays);
    this.settings = new Settings(limit, Duration.ofDays(Math.max(0, minSpanDays)), minPoints);
    this.families =
        Arrays.stream(keys.split(",")).map(String::trim).filter(k -> !k.isEmpty()).toList();
    this.minR2 = minR2;
    this.horizonDays = horizonDays;
  }

  // --- Lecture -----------------------------------------------------------------------------

  /** Prévision de chaque indicateur suivi mesuré sur l'équipement pendant la fenêtre. */
  @Transactional(readOnly = true)
  public List<CapacityForecastResponse> forecasts(UUID equipmentId) {
    requireEquipment(equipmentId);
    Instant now = clock.instant();
    Instant since = now.minus(Duration.ofDays(windowDays));
    List<String> keys =
        jdbc
            .query(
                "SELECT metric_key FROM metric WHERE equipment_id = ? AND collected_at >= ?"
                    + " AND service_id IS NULL AND unit = '%' AND quality <> 'BAD'"
                    + " UNION SELECT metric_key FROM metric_hourly WHERE equipment_id = ?"
                    + " AND hour_start >= ? AND unit = '%'",
                (rs, i) -> rs.getString(1),
                equipmentId,
                Timestamp.from(since),
                equipmentId,
                Timestamp.from(since))
            .stream()
            .filter(this::tracked)
            .sorted()
            .toList();
    return keys.stream().map(key -> response(key, compute(equipmentId, key, now))).toList();
  }

  /** Calcul de la prévision d'un indicateur, sans effet sur les alertes. */
  Result compute(UUID equipmentId, String key, Instant now) {
    return CapacityForecast.compute(points(equipmentId, key, now), now, settings);
  }

  /**
   * Moyennes horaires de la fenêtre : agrégats {@code metric_hourly}, complétés par les mesures
   * brutes regroupées par heure pour les heures pas encore agrégées. Les agrégats horaires ne
   * distinguent pas les sondes : si l'indicateur est aussi mesuré par une sonde de l'équipement,
   * seules les mesures brutes sans service sont lues.
   */
  List<Point> points(UUID equipmentId, String key, Instant now) {
    Instant since = now.minus(Duration.ofDays(windowDays));
    Boolean probed =
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM metric WHERE equipment_id = ? AND metric_key = ?"
                + " AND service_id IS NOT NULL AND collected_at >= ?)",
            Boolean.class,
            equipmentId,
            key,
            Timestamp.from(since));
    List<Point> points = new ArrayList<>();
    Instant rawFrom = since;
    if (!Boolean.TRUE.equals(probed)) {
      points.addAll(
          jdbc.query(
              "SELECT hour_start, avg_value FROM metric_hourly WHERE equipment_id = ?"
                  + " AND metric_key = ? AND unit = '%' AND hour_start >= ? AND hour_start < ?"
                  + " ORDER BY hour_start",
              (rs, i) ->
                  new Point(rs.getTimestamp(1).toInstant(), rs.getBigDecimal(2).doubleValue()),
              equipmentId,
              key,
              Timestamp.from(since),
              Timestamp.from(now)));
      if (!points.isEmpty()) {
        rawFrom = points.get(points.size() - 1).at().plus(Duration.ofHours(1));
      }
    }
    if (rawFrom.isBefore(now)) {
      points.addAll(
          jdbc.query(
              "SELECT date_bin(interval '1 hour', collected_at, timestamptz '2000-01-01 00:00:00+00')"
                  + " AS bucket, avg(metric_value) FROM metric WHERE equipment_id = ?"
                  + " AND metric_key = ? AND service_id IS NULL AND unit = '%'"
                  + " AND quality <> 'BAD' AND collected_at >= ? AND collected_at < ?"
                  + " GROUP BY bucket ORDER BY bucket",
              (rs, i) ->
                  new Point(rs.getTimestamp(1).toInstant(), rs.getBigDecimal(2).doubleValue()),
              equipmentId,
              key,
              Timestamp.from(rawFrom),
              Timestamp.from(now)));
    }
    return points;
  }

  // --- Alertes -----------------------------------------------------------------------------

  @Scheduled(cron = "${forecast.cron:0 7 * * * *}", zone = "UTC")
  public void scheduledScan() {
    if (enabled) {
      guard.run(
          "alerting.forecast", Duration.ofMinutes(50), () -> transaction.execute(s -> scan()));
    }
  }

  /** Un passage complet ; retourne le nombre d'alertes ouvertes. */
  @Transactional
  public int scan() {
    Instant now = clock.instant();
    Timestamp since = Timestamp.from(now.minus(RECENT));
    // Équipements supervisés et hors maintenance, indicateurs en pourcentage mesurés récemment
    // par l'agent ou SNMP (jamais par une sonde).
    var candidates =
        jdbc.query(
            "SELECT m.equipment_id, m.metric_key FROM metric m"
                + " JOIN equipment e ON e.id = m.equipment_id AND e.monitoring_enabled"
                + " AND e.operational_status <> 'MAINTENANCE'"
                + " WHERE m.collected_at >= ? AND m.service_id IS NULL AND m.unit = '%'"
                + " AND m.quality <> 'BAD'"
                + " UNION SELECT h.equipment_id, h.metric_key FROM metric_hourly h"
                + " JOIN equipment e ON e.id = h.equipment_id AND e.monitoring_enabled"
                + " AND e.operational_status <> 'MAINTENANCE'"
                + " WHERE h.hour_start >= ? AND h.unit = '%'",
            (rs, i) -> new Candidate(rs.getObject(1, UUID.class), rs.getString(2)), since, since);
    int opened = 0;
    for (Candidate candidate : candidates) {
      if (tracked(candidate.key()) && evaluate(candidate, now)) {
        opened++;
      }
    }
    return opened;
  }

  private record Candidate(UUID equipmentId, String key) {}

  private boolean evaluate(Candidate candidate, Instant now) {
    Result result = compute(candidate.equipmentId(), candidate.key(), now);
    String prefix = "forecast:" + candidate.equipmentId() + ":" + candidate.key() + "@";
    Optional<Alert> active = alerts.findFirstByOccurrenceKeyStartingWithAndStateIn(prefix, ACTIVE);
    boolean alarming =
        result.status() == ForecastStatus.OK
            && result.r2() >= minR2
            && result.daysToLimit() <= horizonDays;
    if (active.isPresent()) {
      Alert alert = active.get();
      if (result.status() == ForecastStatus.NO_GROWTH) {
        resolve(alert, now, "Plus de tendance à la hausse");
      } else if (result.status() == ForecastStatus.OK
          && result.daysToLimit() > horizonDays * RESOLVE_FACTOR) {
        resolve(alert, now, "Saturation repoussée au-delà de l'horizon");
      } else if (alarming) {
        refresh(alert, candidate.key(), result, now);
      }
      return false;
    }
    if (!alarming) {
      return false;
    }
    Equipment equipment = equipments.findById(candidate.equipmentId()).orElse(null);
    if (equipment == null) {
      return false;
    }
    boolean notify = !underMaintenance(equipment, now);
    var alert =
        new Alert(
            equipment,
            prefix + now.toEpochMilli(),
            AlertType.FORECAST,
            AlertState.OPEN,
            severity(result),
            message(candidate.key(), result),
            now);
    var event = new AlertEvent(alert, AlertEventType.OPENED, now);
    event.setNewState(AlertState.OPEN);
    if (!notify) {
      event.setComment("Maintenance en cours : aucune notification");
    }
    alerts.save(alert);
    events.save(event);
    if (notify) {
      publisher.publishEvent(new AlertChangedEvent(alert.getId(), AlertEventType.OPENED));
    }
    publisher.publishEvent(new AlertActivityEvent(alert.getId(), AlertEventType.OPENED));
    LOG.info(
        "Prévision de saturation ouverte sur {} ({})", candidate.equipmentId(), candidate.key());
    return true;
  }

  /**
   * Alerte active toujours justifiée : passage immédiat en critique quand la saturation se
   * rapproche ; sinon message (et sévérité) mis à jour au plus une fois par jour. Ni l'un ni
   * l'autre ne renvoie de notification : seuls les écrans ouverts sont rafraîchis.
   */
  private void refresh(Alert alert, String key, Result result, Instant now) {
    Severity target = severity(result);
    String message = message(key, result);
    boolean escalate = target == Severity.CRITICAL && alert.getSeverity() != Severity.CRITICAL;
    if (!escalate) {
      if (message.equals(alert.getMessage()) && target == alert.getSeverity()) {
        return;
      }
      Timestamp last =
          jdbc.queryForObject(
              "SELECT max(occurred_at) FROM alert_event WHERE alert_id = ? AND author_id IS NULL"
                  + " AND type IN ('OPENED', 'SEVERITY_CHANGED', 'COMMENTED')",
              Timestamp.class,
              alert.getId());
      if (last != null && last.toInstant().plus(MESSAGE_REFRESH).isAfter(now)) {
        return;
      }
    }
    AlertEvent event;
    if (target != alert.getSeverity()) {
      event = new AlertEvent(alert, AlertEventType.SEVERITY_CHANGED, now);
      event.setComment(
          (target == Severity.CRITICAL ? "Passage en critique : " : "Retour en avertissement : ")
              + message);
      alert.setSeverity(target);
    } else {
      event = new AlertEvent(alert, AlertEventType.COMMENTED, now);
      event.setComment("Prévision mise à jour : " + message);
    }
    alert.setMessage(message);
    alerts.save(alert);
    events.save(event);
    publisher.publishEvent(new AlertActivityEvent(alert.getId(), event.getType()));
  }

  private void resolve(Alert alert, Instant now, String comment) {
    AlertState previous = alert.getState();
    alert.setState(AlertState.RESOLVED);
    alert.setResolvedAt(now);
    alert.setResolutionType(ResolutionType.AUTOMATIC);
    var event = new AlertEvent(alert, AlertEventType.RESOLVED, now);
    event.setPreviousState(previous);
    event.setNewState(AlertState.RESOLVED);
    event.setComment(comment);
    alerts.save(alert);
    events.save(event);
    publisher.publishEvent(new AlertChangedEvent(alert.getId(), AlertEventType.RESOLVED));
    publisher.publishEvent(new AlertActivityEvent(alert.getId(), AlertEventType.RESOLVED));
  }

  private boolean underMaintenance(Equipment equipment, Instant at) {
    UUID siteId = equipment.getSite() == null ? null : equipment.getSite().getId();
    return !maintenances.findCovering(equipment.getId(), siteId, at, MAINTENANCE_STATES).isEmpty();
  }

  private static Severity severity(Result result) {
    return result.daysToLimit() <= CRITICAL_DAYS ? Severity.CRITICAL : Severity.WARNING;
  }

  // --- Libellés ----------------------------------------------------------------------------

  /** « Disque plein dans ~12 jours (tendance +2,0 %/jour, confiance haute) ». */
  static String message(String key, Result result) {
    long days = Math.max(1, Math.round(result.daysToLimit()));
    return subject(key)
        + " dans ~"
        + days
        + (days > 1 ? " jours" : " jour")
        + " (tendance "
        + String.format(Locale.FRANCE, "%+.1f", result.slopePerDay())
        + " %/jour, confiance "
        + CapacityForecast.confidence(result.r2())
        + ")";
  }

  private static String subject(String key) {
    if (inFamily(key, "disk.usage")) {
      return "Disque" + detail(key, "disk.usage") + " plein";
    }
    if (inFamily(key, "memory.usage")) {
      return "Mémoire" + detail(key, "memory.usage") + " pleine";
    }
    return "Indicateur " + key + " plein";
  }

  private static String detail(String key, String family) {
    return key.equals(family) ? "" : " (" + key + ")";
  }

  private static boolean inFamily(String key, String family) {
    return key.equals(family) || key.startsWith(family + ".");
  }

  /** Indicateur suivi : une des familles configurées (la clé elle-même ou une sous-clé). */
  boolean tracked(String key) {
    return families.stream().anyMatch(family -> inFamily(key, family));
  }

  private CapacityForecastResponse response(String key, Result r) {
    return new CapacityForecastResponse(
        key,
        "%",
        round(r.current(), 2),
        round(r.slopePerDay(), 3),
        round(r.r2(), 3),
        round(r.daysToLimit(), 1),
        r.projectedAt(),
        r.status(),
        CapacityForecast.confidence(r.r2()),
        r.limit(),
        windowDays,
        r.points(),
        r.firstAt(),
        r.lastAt());
  }

  private static Double round(Double value, int scale) {
    return value == null
        ? null
        : BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP).doubleValue();
  }

  private void requireEquipment(UUID equipmentId) {
    var site =
        jdbc.query(
            "SELECT site_id FROM equipment WHERE id = ?",
            (rs, i) -> Optional.ofNullable(rs.getObject(1, UUID.class)),
            equipmentId);
    if (site.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Équipement inconnu");
    }
    scope.requireVisible(site.get(0).orElse(null), "Équipement inconnu");
  }
}

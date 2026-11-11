package sn.ept.transdic1.alerting.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import sn.ept.transdic1.inventory.repository.EquipmentRepository;

/**
 * Compare chaque équipement à ses semblables (même type d'équipement, même site) sur un même
 * indicateur. Un commutateur à 80 % de processeur quand les autres du même local sont à 10 % mérite
 * un regard, même sans seuil franchi et même si c'est son habitude : la détection d'anomalies, qui
 * compare un équipement à son propre passé, ne le verrait jamais.
 *
 * <p>Le groupe est fait du même type d'équipement sur le même site, et il faut au moins quatre
 * membres : en dessous, « se démarquer » ne veut rien dire. Seuls les indicateurs en pourcentage
 * sont comparés : comparer des octets par seconde entre des machines de tailles différentes
 * n'apprend rien.
 */
@Service
public class PeerComparisonService {
  private static final Logger LOG = LoggerFactory.getLogger(PeerComparisonService.class);
  private static final Set<AlertState> ACTIVE = Set.of(AlertState.OPEN, AlertState.IN_PROGRESS);

  /** Dispersion plancher : 10 % de la médiane du groupe, au moins 5 points de pourcentage. */
  private static final double FLOOR_RATIO = 0.10;

  private static final double FLOOR_MINIMUM = 5.0;

  private final JdbcTemplate jdbc;
  private final AlertRepository alerts;
  private final AlertEventRepository events;
  private final EquipmentRepository equipments;
  private final ApplicationEventPublisher publisher;
  private final JobGuard guard;
  private final Clock clock;
  private final boolean enabled;
  private final double sensitivity;

  public PeerComparisonService(
      JdbcTemplate jdbc,
      AlertRepository alerts,
      AlertEventRepository events,
      EquipmentRepository equipments,
      ApplicationEventPublisher publisher,
      JobGuard guard,
      Clock clock,
      @Value("${alerting.peer.enabled:true}") boolean enabled,
      @Value("${alerting.peer.sensitivity:4.0}") double sensitivity) {
    this.jdbc = jdbc;
    this.alerts = alerts;
    this.events = events;
    this.equipments = equipments;
    this.publisher = publisher;
    this.guard = guard;
    this.clock = clock;
    this.enabled = enabled;
    this.sensitivity = Math.max(2, Math.min(10, sensitivity));
  }

  @Scheduled(cron = "${alerting.peer.cron:0 5/15 * * * *}", zone = "UTC")
  public void scheduledScan() {
    if (enabled) {
      guard.run("alerting.peer", Duration.ofMinutes(10), this::scan);
    }
  }

  /** Un passage complet ; retourne le nombre d'alertes ouvertes. */
  @Transactional
  public int scan() {
    Instant now = clock.instant();
    // Dernière valeur de chaque (équipement, indicateur) sur le dernier quart d'heure. Les mesures
    // de sonde sont exclues : plusieurs sondes d'un équipement partagent la même clé.
    List<Reading> readings =
        jdbc.query(
            "SELECT e.id, e.site_id, e.equipment_type, m.metric_key, avg(m.metric_value)"
                + " FROM metric m JOIN equipment e ON e.id = m.equipment_id"
                + " AND e.monitoring_enabled AND e.site_id IS NOT NULL"
                + " WHERE m.collected_at >= ? AND m.quality <> 'BAD' AND m.service_id IS NULL"
                + " AND m.unit = '%'"
                + " GROUP BY e.id, e.site_id, e.equipment_type, m.metric_key",
            (rs, i) ->
                new Reading(
                    rs.getObject(1, UUID.class),
                    rs.getObject(2, UUID.class),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getBigDecimal(5).doubleValue()),
            Timestamp.from(now.minus(Duration.ofMinutes(15))));

    Map<GroupKey, List<Reading>> groups = new LinkedHashMap<>();
    for (Reading reading : readings) {
      groups
          .computeIfAbsent(
              new GroupKey(reading.siteId(), reading.equipmentType(), reading.key()),
              key -> new ArrayList<>())
          .add(reading);
    }
    int opened = 0;
    for (var entry : groups.entrySet()) {
      opened += evaluateGroup(entry.getKey(), entry.getValue(), now);
    }
    return opened;
  }

  private record GroupKey(UUID siteId, String equipmentType, String key) {}

  private record Reading(
      UUID equipmentId, UUID siteId, String equipmentType, String key, double value) {}

  private int evaluateGroup(GroupKey key, List<Reading> members, Instant now) {
    Optional<PeerOutlier.Group> reference =
        PeerOutlier.group(
            members.stream().map(Reading::value).toList(), FLOOR_RATIO, FLOOR_MINIMUM);
    if (reference.isEmpty()) {
      return 0;
    }
    PeerOutlier.Group group = reference.get();
    int opened = 0;
    for (Reading member : members) {
      if (evaluate(key, group, member, now)) {
        opened++;
      }
    }
    return opened;
  }

  private boolean evaluate(GroupKey key, PeerOutlier.Group group, Reading member, Instant now) {
    String prefix = "peer:" + member.equipmentId() + ":" + member.key() + ":";
    Optional<Alert> active = alerts.findFirstByOccurrenceKeyStartingWithAndStateIn(prefix, ACTIVE);
    if (active.isPresent()) {
      if (PeerOutlier.isBackInLine(group, member.value(), sensitivity)) {
        resolve(active.get(), now);
      }
      return false;
    }
    if (!PeerOutlier.standsOut(group, member.value(), sensitivity)) {
      return false;
    }
    var equipment = equipments.findById(member.equipmentId()).orElse(null);
    if (equipment == null) {
      return false;
    }
    String message =
        "Se démarque de ses semblables : "
            + member.key()
            + " = "
            + round(member.value())
            + " % alors que les "
            + (group.size() - 1)
            + " autres équipements du même type sur ce site sont autour de "
            + round(group.median())
            + " %";
    var alert =
        new Alert(
            equipment,
            prefix + now.toEpochMilli(),
            AlertType.PEER,
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
    LOG.info("Écart aux semblables sur {} ({})", member.equipmentId(), member.key());
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
    event.setComment("Revenu dans la moyenne de ses semblables");
    alerts.save(alert);
    events.save(event);
    publisher.publishEvent(new AlertChangedEvent(alert.getId(), AlertEventType.RESOLVED));
    publisher.publishEvent(new AlertActivityEvent(alert.getId(), AlertEventType.RESOLVED));
  }

  private static String round(double value) {
    return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).toPlainString();
  }
}

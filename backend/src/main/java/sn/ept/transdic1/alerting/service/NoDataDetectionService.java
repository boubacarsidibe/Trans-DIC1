package sn.ept.transdic1.alerting.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
 * Absence de donnée par indicateur : un indicateur qui arrivait régulièrement cesse d'arriver,
 * alors que l'équipement répond par ailleurs. Un disque démonté, un service disparu de l'agent ou
 * un OID qui ne répond plus se voient ainsi, là où la perte de collecte ne voit qu'un équipement
 * entièrement muet.
 *
 * <p>Deux garde-fous contre le bruit :
 *
 * <ul>
 *   <li>l'indicateur doit avoir une habitude : assez de relevés sur la veille pour qu'un silence
 *       veuille dire quelque chose ;
 *   <li>un équipement déjà en perte de collecte n'ouvre rien : il est muet pour tout, une alerte
 *       par indicateur n'apprendrait rien et noierait l'opérateur.
 * </ul>
 */
@Service
public class NoDataDetectionService {
  private static final Logger LOG = LoggerFactory.getLogger(NoDataDetectionService.class);
  private static final Set<AlertState> ACTIVE = Set.of(AlertState.OPEN, AlertState.IN_PROGRESS);

  /** Relevés minimaux sur la veille pour qu'un indicateur soit tenu pour régulier. */
  static final int MIN_SAMPLES = 12;

  private final JdbcTemplate jdbc;
  private final AlertRepository alerts;
  private final AlertEventRepository events;
  private final EquipmentRepository equipments;
  private final ApplicationEventPublisher publisher;
  private final JobGuard guard;
  private final Clock clock;
  private final boolean enabled;
  private final Duration silence;

  public NoDataDetectionService(
      JdbcTemplate jdbc,
      AlertRepository alerts,
      AlertEventRepository events,
      EquipmentRepository equipments,
      ApplicationEventPublisher publisher,
      JobGuard guard,
      Clock clock,
      @Value("${alerting.no-data.enabled:true}") boolean enabled,
      @Value("${alerting.no-data.after-minutes:30}") long afterMinutes) {
    this.jdbc = jdbc;
    this.alerts = alerts;
    this.events = events;
    this.equipments = equipments;
    this.publisher = publisher;
    this.guard = guard;
    this.clock = clock;
    this.enabled = enabled;
    // Moins de cinq minutes, un simple retard de collecte ouvrirait des alertes.
    this.silence = Duration.ofMinutes(Math.max(5, afterMinutes));
  }

  @Scheduled(cron = "${alerting.no-data.cron:0 10/15 * * * *}", zone = "UTC")
  public void scheduledScan() {
    if (enabled) {
      guard.run("alerting.no-data", Duration.ofMinutes(10), this::scan);
    }
  }

  /** Un passage complet ; retourne le nombre d'alertes ouvertes. */
  @Transactional
  public int scan() {
    Instant now = clock.instant();
    // Indicateurs réguliers de la veille, avec leur dernier relevé. Les mesures de sonde sont
    // écartées : une sonde désactivée a son propre chemin (ses alertes sont résolues).
    List<Silent> silent =
        jdbc.query(
            "SELECT e.id, m.metric_key, max(m.collected_at) AS last_seen, count(*) AS samples"
                + " FROM metric m JOIN equipment e ON e.id = m.equipment_id AND e.monitoring_enabled"
                + " WHERE m.collected_at >= ? AND m.service_id IS NULL"
                + " GROUP BY e.id, m.metric_key"
                + " HAVING count(*) >= ? AND max(m.collected_at) < ?",
            (rs, i) ->
                new Silent(
                    rs.getObject(1, UUID.class), rs.getString(2), rs.getTimestamp(3).toInstant()),
            Timestamp.from(now.minus(Duration.ofDays(1))),
            MIN_SAMPLES,
            Timestamp.from(now.minus(silence)));

    int opened = 0;
    for (Silent value : silent) {
      if (open(value, now)) {
        opened++;
      }
    }
    return opened + resolveReturned(now);
  }

  private record Silent(UUID equipmentId, String key, Instant lastSeen) {}

  private boolean open(Silent silent, Instant now) {
    String prefix = "nodata:" + silent.equipmentId() + ":" + silent.key() + ":";
    if (alerts.findFirstByOccurrenceKeyStartingWithAndStateIn(prefix, ACTIVE).isPresent()) {
      return false;
    }
    // Équipement entièrement muet : la perte de collecte dit déjà tout.
    if (hasActiveCollectionAlert(silent.equipmentId())) {
      return false;
    }
    var equipment = equipments.findById(silent.equipmentId()).orElse(null);
    if (equipment == null) {
      return false;
    }
    long minutes = Duration.between(silent.lastSeen(), now).toMinutes();
    var alert =
        new Alert(
            equipment,
            prefix + now.toEpochMilli(),
            AlertType.NO_DATA,
            AlertState.OPEN,
            Severity.WARNING,
            "Plus de mesure pour "
                + silent.key()
                + " depuis "
                + minutes
                + " min, alors que l'équipement répond par ailleurs",
            now);
    var event = new AlertEvent(alert, AlertEventType.OPENED, now);
    event.setNewState(AlertState.OPEN);
    alerts.save(alert);
    events.save(event);
    publisher.publishEvent(new AlertChangedEvent(alert.getId(), AlertEventType.OPENED));
    publisher.publishEvent(new AlertActivityEvent(alert.getId(), AlertEventType.OPENED));
    LOG.info("Absence de donnée sur {} ({})", silent.equipmentId(), silent.key());
    return true;
  }

  private boolean hasActiveCollectionAlert(UUID equipmentId) {
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM alert WHERE equipment_id = ? AND type = 'COLLECTION'"
                + " AND state IN ('OPEN', 'IN_PROGRESS')",
            Integer.class,
            equipmentId);
    return count != null && count > 0;
  }

  /** Les mesures sont revenues : l'alerte se résout seule. Retourne le nombre de résolutions. */
  private int resolveReturned(Instant now) {
    List<Alert> active =
        alerts.findByTypeAndStateIn(AlertType.NO_DATA, ACTIVE).stream()
            .filter(alert -> measuredSince(alert, now))
            .toList();
    for (Alert alert : active) {
      AlertState previous = alert.getState();
      alert.setState(AlertState.RESOLVED);
      alert.setResolvedAt(now);
      alert.setResolutionType(ResolutionType.AUTOMATIC);
      var event = new AlertEvent(alert, AlertEventType.RESOLVED, now);
      event.setPreviousState(previous);
      event.setNewState(AlertState.RESOLVED);
      event.setComment("Les mesures sont revenues");
      alerts.save(alert);
      events.save(event);
      publisher.publishEvent(new AlertChangedEvent(alert.getId(), AlertEventType.RESOLVED));
      publisher.publishEvent(new AlertActivityEvent(alert.getId(), AlertEventType.RESOLVED));
    }
    return active.size();
  }

  /** Vrai si l'indicateur de cette alerte a de nouveau été mesuré dans la fenêtre de silence. */
  private boolean measuredSince(Alert alert, Instant now) {
    Optional<String> key = keyOf(alert);
    if (key.isEmpty()) {
      return false;
    }
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM metric WHERE equipment_id = ? AND metric_key = ?"
                + " AND service_id IS NULL AND collected_at >= ?",
            Integer.class,
            alert.getEquipment().getId(),
            key.get(),
            Timestamp.from(now.minus(silence)));
    return count != null && count > 0;
  }

  /** Indicateur porté par la clé d'occurrence {@code nodata:<équipement>:<clé>:<instant>}. */
  static Optional<String> keyOf(Alert alert) {
    String occurrence = alert.getOccurrenceKey();
    String prefix = "nodata:" + alert.getEquipment().getId() + ":";
    if (occurrence == null || !occurrence.startsWith(prefix)) {
      return Optional.empty();
    }
    int end = occurrence.lastIndexOf(':');
    return end > prefix.length()
        ? Optional.of(occurrence.substring(prefix.length(), end))
        : Optional.empty();
  }
}

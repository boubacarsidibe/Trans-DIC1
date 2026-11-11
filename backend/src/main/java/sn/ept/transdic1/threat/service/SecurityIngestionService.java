package sn.ept.transdic1.threat.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import sn.ept.transdic1.alerting.domain.Alert;
import sn.ept.transdic1.alerting.domain.AlertActivityEvent;
import sn.ept.transdic1.alerting.domain.AlertChangedEvent;
import sn.ept.transdic1.alerting.domain.AlertEvent;
import sn.ept.transdic1.alerting.domain.AlertEventType;
import sn.ept.transdic1.alerting.domain.AlertState;
import sn.ept.transdic1.alerting.domain.AlertType;
import sn.ept.transdic1.alerting.domain.Severity;
import sn.ept.transdic1.alerting.repository.AlertEventRepository;
import sn.ept.transdic1.alerting.repository.AlertRepository;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.threat.domain.SecurityEvent;
import sn.ept.transdic1.threat.domain.SecurityEventKind;
import sn.ept.transdic1.threat.dto.LogBatch;
import sn.ept.transdic1.threat.repository.SecurityEventRepository;

/**
 * Reçoit les lignes de journal d'un agent, les juge et ouvre des alertes de sécurité.
 *
 * <p>Trois détections, toutes explicables :
 *
 * <ul>
 *   <li><b>Attaque dans une requête web</b> : signatures à score cumulé ({@link
 *       WebAttackSignatures}). Une seule requête suffit.
 *   <li><b>Force brute</b> : échecs d'authentification répétés depuis une même adresse.
 *   <li><b>Balayage web</b> : requêtes en erreur en rafale depuis une même adresse.
 * </ul>
 *
 * <p>Ce qui n'est pas gardé : une requête web ordinaire ne laisse aucune ligne. Seules les
 * tentatives d'authentification, les attaques reconnues et les erreurs web sont conservées, et
 * elles sont purgées au bout de quelques jours (données personnelles).
 *
 * <p>Ce n'est pas un pare-feu : on lit un journal, donc on voit l'attaque après coup, et rien n'est
 * bloqué. Le blocage automatique couperait un usager sur un faux positif ; c'est une décision à
 * prendre avec le CRI.
 */
@Service
public class SecurityIngestionService {
  private static final Logger LOG = LoggerFactory.getLogger(SecurityIngestionService.class);
  private static final Set<AlertState> ACTIVE = Set.of(AlertState.OPEN, AlertState.IN_PROGRESS);

  /** Une adresse n'ouvre qu'une alerte à la fois par équipement et par nature. */
  private static final String AUTH_KEY = "security:auth:";

  private static final String SCAN_KEY = "security:scan:";
  private static final String ATTACK_KEY = "security:attack:";

  private final EquipmentRepository equipments;
  private final SecurityEventRepository events;
  private final AlertRepository alerts;
  private final AlertEventRepository alertEvents;
  private final JdbcTemplate jdbc;
  private final ApplicationEventPublisher publisher;
  private final Clock clock;
  private final Duration window;
  private final int authThreshold;
  private final int scanThreshold;

  public SecurityIngestionService(
      EquipmentRepository equipments,
      SecurityEventRepository events,
      AlertRepository alerts,
      AlertEventRepository alertEvents,
      JdbcTemplate jdbc,
      ApplicationEventPublisher publisher,
      Clock clock,
      @Value("${threat.window-minutes:5}") long windowMinutes,
      @Value("${threat.auth-failures:10}") int authThreshold,
      @Value("${threat.web-errors:40}") int scanThreshold) {
    this.equipments = equipments;
    this.events = events;
    this.alerts = alerts;
    this.alertEvents = alertEvents;
    this.jdbc = jdbc;
    this.publisher = publisher;
    this.clock = clock;
    this.window = Duration.ofMinutes(Math.max(1, windowMinutes));
    this.authThreshold = Math.max(3, authThreshold);
    this.scanThreshold = Math.max(10, scanThreshold);
  }

  /** Ce que l'agent apprend de son envoi : combien de lignes retenues, combien d'alertes. */
  public record IngestionResult(int stored, int attacks, int alerts) {}

  @Transactional
  public IngestionResult ingest(UUID equipmentId, LogBatch batch) {
    Equipment equipment =
        equipments
            .findById(equipmentId)
            .filter(Equipment::isMonitoringEnabled)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Équipement inconnu ou non supervisé"));
    Instant now = clock.instant();
    Instant limit = now.plus(Duration.ofMinutes(5));
    int stored = 0;
    int attacks = 0;
    for (LogBatch.LogEntry entry : batch.entries()) {
      if (entry.occurredAt().isAfter(limit)) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Horodatage dans le futur");
      }
      SecurityEvent event = judge(equipment, entry, now);
      if (event != null) {
        events.save(event);
        stored++;
        if (event.getKind() == SecurityEventKind.WEB_ATTACK) {
          attacks++;
        }
      }
    }
    int opened = raiseAlerts(equipment, batch, now);
    if (attacks > 0) {
      LOG.info("{} requête(s) d'attaque reçue(s) de {}", attacks, equipment.getName());
    }
    return new IngestionResult(stored, attacks, opened);
  }

  /** Décide si une ligne mérite d'être conservée, et sous quelle nature. */
  private SecurityEvent judge(Equipment equipment, LogBatch.LogEntry entry, Instant now) {
    String kind = entry.kind() == null ? "" : entry.kind().toUpperCase(Locale.ROOT);
    if ("AUTH_FAILURE".equals(kind) || "AUTH_SUCCESS".equals(kind)) {
      var event =
          new SecurityEvent(
              equipment,
              "AUTH_FAILURE".equals(kind)
                  ? SecurityEventKind.AUTH_FAILURE
                  : SecurityEventKind.AUTH_SUCCESS,
              entry.occurredAt(),
              now);
      event.setSourceAddress(entry.sourceAddress());
      event.setUsername(entry.username());
      return event;
    }
    if (!"WEB".equals(kind)) {
      return null;
    }
    var verdict = WebAttackSignatures.examine(entry.request());
    boolean error = entry.status() != null && entry.status() >= 400;
    if (!verdict.isAttack() && !error) {
      // Requête ordinaire : rien n'est conservé, ni l'URL ni l'adresse de l'usager.
      return null;
    }
    var event =
        new SecurityEvent(
            equipment,
            verdict.isAttack() ? SecurityEventKind.WEB_ATTACK : SecurityEventKind.WEB_ERROR,
            entry.occurredAt(),
            now);
    event.setSourceAddress(entry.sourceAddress());
    event.setMethod(entry.method());
    event.setRequest(truncate(entry.request()));
    event.setStatus(entry.status());
    event.setScore(verdict.score());
    if (!verdict.families().isEmpty()) {
      event.setFamilies(truncateFamilies(verdict.summary()));
    }
    return event;
  }

  /** Ouvre les alertes que ce lot justifie, une au plus par adresse et par nature. */
  private int raiseAlerts(Equipment equipment, LogBatch batch, Instant now) {
    // Les comptages se font en SQL : les lignes de ce lot doivent déjà être en base.
    events.flush();
    int opened = 0;
    for (String address : addresses(batch)) {
      opened += attackAlert(equipment, address, now);
      opened += authAlert(equipment, address, now);
      opened += scanAlert(equipment, address, now);
    }
    return opened;
  }

  private List<String> addresses(LogBatch batch) {
    return batch.entries().stream()
        .map(LogBatch.LogEntry::sourceAddress)
        .filter(address -> address != null && !address.isBlank())
        .distinct()
        .limit(50)
        .toList();
  }

  private int attackAlert(Equipment equipment, String address, Instant now) {
    var counts =
        jdbc.queryForObject(
            "SELECT count(*), max(families) FROM security_event WHERE equipment_id = ?"
                + " AND source_address = ? AND kind = 'WEB_ATTACK' AND occurred_at >= ?",
            (rs, i) -> new Object[] {rs.getInt(1), rs.getString(2)},
            equipment.getId(),
            address,
            Timestamp.from(now.minus(window)));
    int attacks = counts == null ? 0 : (int) counts[0];
    if (attacks == 0) {
      return 0;
    }
    String families = counts[1] == null ? "" : (String) counts[1];
    String message =
        "Attaque web depuis "
            + address
            + " sur "
            + equipment.getName()
            + " : "
            + attacks
            + (attacks > 1 ? " requêtes reconnues" : " requête reconnue")
            + (families.isBlank() ? "" : " (" + families + ")");
    return open(equipment, ATTACK_KEY, address, Severity.CRITICAL, message, now);
  }

  private int authAlert(Equipment equipment, String address, Instant now) {
    var counts =
        jdbc.queryForObject(
            "SELECT count(*), count(DISTINCT username) FROM security_event"
                + " WHERE equipment_id = ? AND source_address = ? AND kind = 'AUTH_FAILURE'"
                + " AND occurred_at >= ?",
            (rs, i) -> new int[] {rs.getInt(1), rs.getInt(2)},
            equipment.getId(),
            address,
            Timestamp.from(now.minus(window)));
    int failures = counts == null ? 0 : counts[0];
    if (!BruteForce.isAttack(failures, authThreshold)) {
      return 0;
    }
    String message =
        BruteForce.explainAuth(
            address, failures, counts[1], (int) window.toMinutes(), equipment.getName());
    return open(equipment, AUTH_KEY, address, Severity.CRITICAL, message, now);
  }

  private int scanAlert(Equipment equipment, String address, Instant now) {
    Integer errors =
        jdbc.queryForObject(
            "SELECT count(*) FROM security_event WHERE equipment_id = ? AND source_address = ?"
                + " AND kind = 'WEB_ERROR' AND occurred_at >= ?",
            Integer.class,
            equipment.getId(),
            address,
            Timestamp.from(now.minus(window)));
    if (errors == null || !BruteForce.isAttack(errors, scanThreshold)) {
      return 0;
    }
    String message =
        BruteForce.explainScan(address, errors, (int) window.toMinutes(), equipment.getName());
    return open(equipment, SCAN_KEY, address, Severity.WARNING, message, now);
  }

  /** Ouvre l'alerte si elle n'est pas déjà ouverte pour cette adresse et cette nature. */
  private int open(
      Equipment equipment,
      String prefix,
      String address,
      Severity severity,
      String message,
      Instant now) {
    String occurrence = prefix + equipment.getId() + ":" + address + ":";
    Optional<Alert> active =
        alerts.findFirstByOccurrenceKeyStartingWithAndStateIn(occurrence, ACTIVE);
    if (active.isPresent()) {
      // L'attaque continue : on met le message à jour plutôt que d'empiler les alertes.
      active.get().setMessage(message);
      alerts.save(active.get());
      return 0;
    }
    var alert =
        new Alert(
            equipment,
            occurrence + now.toEpochMilli(),
            AlertType.SECURITY,
            AlertState.OPEN,
            severity,
            message,
            now);
    var event = new AlertEvent(alert, AlertEventType.OPENED, now);
    event.setNewState(AlertState.OPEN);
    alerts.save(alert);
    alertEvents.save(event);
    publisher.publishEvent(new AlertChangedEvent(alert.getId(), AlertEventType.OPENED));
    publisher.publishEvent(new AlertActivityEvent(alert.getId(), AlertEventType.OPENED));
    return 1;
  }

  private static String truncate(String value) {
    if (value == null) {
      return null;
    }
    return value.length() > 2000 ? value.substring(0, 2000) : value;
  }

  private static String truncateFamilies(String value) {
    return value.length() > 255 ? value.substring(0, 255) : value;
  }
}

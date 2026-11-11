package sn.ept.transdic1.threat.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import sn.ept.transdic1.alerting.domain.Severity;
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
 *   <li><b>Forme inhabituelle</b> : modèle appris ({@link UrlModel}) sur les suites de caractères,
 *       pour les charges utiles qu'aucune signature ne couvre. Moins sûr qu'une signature : il en
 *       faut plusieurs d'une même adresse, et cela n'ouvre jamais qu'un avertissement.
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

  /** Ce que l'écran et l'alerte affichent pour une requête retenue par le seul modèle. */
  static final String SUSPECT_LABEL = "Forme inhabituelle (modèle appris)";

  private final EquipmentRepository equipments;
  private final SecurityEventRepository events;
  private final SecurityAlerts alerts;
  private final SecurityBehaviourService behaviour;
  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final UrlModel model;
  private final Duration window;
  private final int authThreshold;
  private final int scanThreshold;
  private final double modelThreshold;
  private final int suspectThreshold;

  public SecurityIngestionService(
      EquipmentRepository equipments,
      SecurityEventRepository events,
      SecurityAlerts alerts,
      SecurityBehaviourService behaviour,
      JdbcTemplate jdbc,
      Clock clock,
      UrlModel model,
      @Value("${threat.window-minutes:5}") long windowMinutes,
      @Value("${threat.auth-failures:10}") int authThreshold,
      @Value("${threat.web-errors:40}") int scanThreshold,
      @Value("${threat.model.threshold:0.9}") double modelThreshold,
      @Value("${threat.suspect-requests:5}") int suspectThreshold) {
    this.equipments = equipments;
    this.events = events;
    this.alerts = alerts;
    this.behaviour = behaviour;
    this.jdbc = jdbc;
    this.clock = clock;
    this.model = model;
    this.window = Duration.ofMinutes(Math.max(1, windowMinutes));
    this.authThreshold = Math.max(3, authThreshold);
    this.scanThreshold = Math.max(10, scanThreshold);
    // Un seuil bas rendrait le modèle bavard : on ne descend pas sous 0,5, et jamais au-dessus
    // de 1, sans quoi plus rien ne passerait.
    this.modelThreshold = Math.min(1, Math.max(0.5, modelThreshold));
    this.suspectThreshold = Math.max(2, suspectThreshold);
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
    // Les connexions sont jugées ici, avant le moindre enregistrement : la question « ce compte
    // s'est-il déjà connecté depuis cette adresse ? » n'a de sens que sur l'état d'avant le lot.
    int opened = behaviour.checkLogins(equipment, batch, now);
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
    opened += raiseAlerts(equipment, batch, now);
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
    // Le modèle n'est interrogé que si aucune signature n'a parlé : quand une règle reconnaît la
    // requête, elle dit déjà mieux ce dont il s'agit.
    double probability = verdict.isAttack() ? 0 : model.probability(entry.request());
    boolean suspect = probability >= modelThreshold;
    if (!verdict.isAttack() && !suspect && !error) {
      // Requête ordinaire : rien n'est conservé, ni l'URL ni l'adresse de l'usager.
      return null;
    }
    SecurityEventKind webKind;
    if (verdict.isAttack()) {
      webKind = SecurityEventKind.WEB_ATTACK;
    } else if (suspect) {
      webKind = SecurityEventKind.WEB_SUSPECT;
    } else {
      webKind = SecurityEventKind.WEB_ERROR;
    }
    var event = new SecurityEvent(equipment, webKind, entry.occurredAt(), now);
    event.setSourceAddress(entry.sourceAddress());
    event.setMethod(entry.method());
    event.setRequest(truncate(entry.request()));
    event.setStatus(entry.status());
    if (webKind == SecurityEventKind.WEB_SUSPECT) {
      // Le score affiché est la confiance du modèle, en pour cent : il n'y a pas de famille à
      // nommer, le modèle ne juge que la forme.
      event.setScore((int) Math.round(probability * 100));
      event.setFamilies(SUSPECT_LABEL);
    } else {
      event.setScore(verdict.score());
      if (!verdict.families().isEmpty()) {
        event.setFamilies(truncateFamilies(verdict.summary()));
      }
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
      opened += suspectAlert(equipment, address, now);
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
    return alerts.open(
        equipment, SecurityAlerts.Subject.ATTACK, address, Severity.CRITICAL, message, now);
  }

  /**
   * Avertissement quand le modèle a trouvé plusieurs requêtes de forme inhabituelle venant de la
   * même adresse. Une seule ne suffit pas : le modèle se trompe, et une alerte critique sur une
   * requête légitime mal formée ferait perdre confiance à l'équipe.
   */
  private int suspectAlert(Equipment equipment, String address, Instant now) {
    Integer suspects =
        jdbc.queryForObject(
            "SELECT count(*) FROM security_event WHERE equipment_id = ? AND source_address = ?"
                + " AND kind = 'WEB_SUSPECT' AND occurred_at >= ?",
            Integer.class,
            equipment.getId(),
            address,
            Timestamp.from(now.minus(window)));
    if (suspects == null || suspects < suspectThreshold) {
      return 0;
    }
    String message =
        "Requêtes de forme inhabituelle depuis "
            + address
            + " sur "
            + equipment.getName()
            + " : "
            + suspects
            + " en "
            + window.toMinutes()
            + " min, reconnues par le modèle appris et par aucune signature. À regarder dans le"
            + " journal du serveur avant de conclure.";
    return alerts.open(
        equipment, SecurityAlerts.Subject.SUSPECT, address, Severity.WARNING, message, now);
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
    return alerts.open(
        equipment, SecurityAlerts.Subject.AUTH, address, Severity.CRITICAL, message, now);
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
    return alerts.open(
        equipment, SecurityAlerts.Subject.SCAN, address, Severity.WARNING, message, now);
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

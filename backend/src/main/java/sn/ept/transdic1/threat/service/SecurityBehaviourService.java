package sn.ept.transdic1.threat.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.alerting.domain.Severity;
import sn.ept.transdic1.alerting.service.AnomalyDetector;
import sn.ept.transdic1.common.scheduling.JobGuard;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.threat.dto.LogBatch;

/**
 * Comportement inhabituel d'un compte ou d'un serveur (S5 du plan).
 *
 * <p>Les détections de l'ADR 0028 comptent sur une fenêtre courte : dix échecs en cinq minutes,
 * quarante erreurs en cinq minutes. Une attaque patiente passe dessous sans rien déclencher, et un
 * compte volé dont le mot de passe est juste ne produit aucun échec du tout. Deux détections
 * s'ajoutent donc ici, l'une et l'autre fondées sur ce que l'on a l'habitude de voir :
 *
 * <ul>
 *   <li><b>Connexion depuis une adresse jamais vue</b> : un compte qui se connait des habitudes —
 *       au moins {@code threat.known-logins} connexions réussies sur la période conservée — réussit
 *       soudain depuis une adresse d'où il ne s'est jamais connecté.
 *   <li><b>Volume inhabituel dans l'heure</b> : le nombre d'échecs d'authentification ou de
 *       requêtes en erreur d'une heure, comparé à la normale du même créneau les jours précédents,
 *       par la statistique robuste déjà employée pour les indicateurs ({@link AnomalyDetector}, ADR
 *       0015) : médiane et écart médian, anciens incidents écartés.
 * </ul>
 *
 * <p>Les deux n'ouvrent que des <b>avertissements</b> : ce sont des changements d'habitude, pas des
 * attaques prouvées. Un enseignant en déplacement se connecte depuis une adresse nouvelle, et un
 * jour de rentrée ne ressemble à aucun autre.
 */
@Service
public class SecurityBehaviourService {
  private static final Logger LOG = LoggerFactory.getLogger(SecurityBehaviourService.class);

  /** Heure locale de l'école : « l'habitude de 14 h » n'a de sens que dans un fuseau. */
  private static final ZoneId ZONE = ZoneId.of("Africa/Dakar");

  /** Natures dont le volume horaire est surveillé, avec leur nom dans les messages. */
  private static final Map<String, String> WATCHED =
      Map.of(
          "AUTH_FAILURE", "échecs d'authentification",
          "WEB_ERROR", "requêtes en erreur");

  private final JdbcTemplate jdbc;
  private final EquipmentRepository equipments;
  private final SecurityAlerts alerts;
  private final JobGuard guard;
  private final Clock clock;
  private final boolean loginEnabled;
  private final boolean volumeEnabled;
  private final int knownLogins;
  private final double sensitivity;
  private final int volumeFloor;
  private final Duration history;

  public SecurityBehaviourService(
      JdbcTemplate jdbc,
      EquipmentRepository equipments,
      SecurityAlerts alerts,
      JobGuard guard,
      Clock clock,
      @Value("${threat.unusual-login.enabled:true}") boolean loginEnabled,
      @Value("${threat.unusual-volume.enabled:true}") boolean volumeEnabled,
      @Value("${threat.known-logins:5}") int knownLogins,
      @Value("${threat.volume-sensitivity:4.0}") double sensitivity,
      @Value("${threat.volume-floor:20}") int volumeFloor,
      @Value("${threat.retention-days:30}") long retentionDays) {
    this.jdbc = jdbc;
    this.equipments = equipments;
    this.alerts = alerts;
    this.guard = guard;
    this.clock = clock;
    this.loginEnabled = loginEnabled;
    this.volumeEnabled = volumeEnabled;
    // Moins de trois connaissances d'un compte ne font pas une habitude.
    this.knownLogins = Math.max(3, knownLogins);
    this.sensitivity = Math.max(2, sensitivity);
    // Sans plancher, trois erreurs valent une alerte sur un serveur d'ordinaire muet.
    this.volumeFloor = Math.max(5, volumeFloor);
    // On ne peut comparer qu'à ce qui est encore conservé.
    this.history = Duration.ofDays(Math.max(2, retentionDays));
  }

  // ------------------------------------------------------------------ connexions inhabituelles

  /**
   * Juge les connexions réussies d'un lot de journaux. <b>À appeler avant tout enregistrement</b> :
   * la question posée porte sur l'état d'avant le lot.
   *
   * @return le nombre d'alertes ouvertes
   */
  public int checkLogins(Equipment equipment, LogBatch batch, Instant now) {
    if (!loginEnabled) {
      return 0;
    }
    Instant since = now.minus(history);
    int opened = 0;
    for (Login login : logins(batch)) {
      opened += checkLogin(equipment, login, since, now);
    }
    return opened;
  }

  /** Un compte et l'adresse d'où il vient de se connecter. */
  private record Login(String username, String address) {}

  private List<Login> logins(LogBatch batch) {
    var seen = new LinkedHashSet<Login>();
    for (LogBatch.LogEntry entry : batch.entries()) {
      boolean success = "AUTH_SUCCESS".equalsIgnoreCase(entry.kind() == null ? "" : entry.kind());
      if (success && notBlank(entry.username()) && notBlank(entry.sourceAddress())) {
        seen.add(new Login(entry.username().strip(), entry.sourceAddress().strip()));
      }
      if (seen.size() >= 50) {
        break;
      }
    }
    return List.copyOf(seen);
  }

  private int checkLogin(Equipment equipment, Login login, Instant since, Instant now) {
    int[] counts =
        jdbc.queryForObject(
            "SELECT count(*), count(*) FILTER (WHERE source_address = ?),"
                + " count(DISTINCT source_address) FROM security_event"
                + " WHERE equipment_id = ? AND kind = 'AUTH_SUCCESS' AND username = ?"
                + " AND occurred_at >= ?",
            (rs, i) -> new int[] {rs.getInt(1), rs.getInt(2), rs.getInt(3)},
            login.address(),
            equipment.getId(),
            login.username(),
            Timestamp.from(since));
    if (counts == null) {
      return 0;
    }
    int total = counts[0];
    int fromThisAddress = counts[1];
    int addresses = counts[2];
    if (fromThisAddress > 0 || total < knownLogins) {
      // Adresse déjà connue, ou compte dont on ne connaît pas encore les habitudes.
      return 0;
    }
    String message =
        "Connexion de "
            + login.username()
            + " sur "
            + equipment.getName()
            + " depuis "
            + login.address()
            + ", adresse d'où ce compte ne s'était jamais connecté ("
            + total
            + " connexions depuis "
            + addresses
            + (addresses > 1 ? " autres adresses" : " autre adresse")
            + " sur "
            + history.toDays()
            + " jours). À confirmer auprès de la personne avant d'en conclure quoi que ce soit.";
    return alerts.open(
        equipment, SecurityAlerts.Subject.LOGIN, login.username(), Severity.WARNING, message, now);
  }

  // ------------------------------------------------------------------- volume inhabituel

  /** Examen du volume de l'heure écoulée, une fois par heure. */
  @Scheduled(cron = "${threat.volume-cron:0 12 * * * *}", zone = "UTC")
  public void scheduledScan() {
    guard.run("threat.volume", Duration.ofMinutes(10), this::scanUnusualVolume);
  }

  /**
   * Compare le volume de la dernière heure complète à l'habitude du même créneau.
   *
   * @return le nombre d'alertes ouvertes
   */
  @Transactional
  public int scanUnusualVolume() {
    if (!volumeEnabled) {
      return 0;
    }
    Instant end = clock.instant().truncatedTo(ChronoUnit.HOURS);
    Instant start = end.minus(Duration.ofHours(1));
    int opened = 0;
    for (Map.Entry<String, String> watched : WATCHED.entrySet()) {
      opened += scanKind(watched.getKey(), watched.getValue(), start, end);
    }
    if (opened > 0) {
      LOG.info("Volume inhabituel de journaux de sécurité : {} alerte(s) ouverte(s)", opened);
    }
    return opened;
  }

  private int scanKind(String kind, String label, Instant start, Instant end) {
    Map<UUID, Integer> current = countsByEquipment(kind, start, end);
    int opened = 0;
    for (Map.Entry<UUID, Integer> entry : current.entrySet()) {
      if (entry.getValue() < volumeFloor) {
        // Trop peu pour signifier quoi que ce soit, même comparé à un créneau d'ordinaire vide.
        continue;
      }
      Optional<AnomalyDetector.Baseline> baseline = baseline(entry.getKey(), kind, start);
      if (baseline.isEmpty()) {
        continue;
      }
      double score = score(baseline.get(), entry.getValue());
      if (score < sensitivity) {
        // Seul l'excès intéresse : une heure plus calme que d'habitude n'est pas une alerte.
        continue;
      }
      Optional<Equipment> equipment = equipments.findById(entry.getKey());
      if (equipment.isEmpty()) {
        continue;
      }
      opened +=
          alerts.open(
              equipment.get(),
              SecurityAlerts.Subject.VOLUME,
              kind,
              Severity.WARNING,
              message(equipment.get(), label, entry.getValue(), baseline.get(), start),
              end);
    }
    return opened;
  }

  /**
   * Écart en nombre d'écarts-types, avec un plancher adapté à un comptage.
   *
   * <p>{@link AnomalyDetector} plancher l'écart-type à 1 % de la moyenne, ce qui convient à un taux
   * d'occupation mais pas à un nombre d'événements : un créneau qui vaut 30 tous les jours aurait
   * un écart-type de 0,3 et 32 passerait pour une anomalie à 6 écarts-types. Le bruit naturel d'un
   * comptage croît comme sa racine (loi de Poisson) : c'est ce plancher-là qu'on retient quand
   * l'historique est plus régulier que le hasard ne le permet.
   */
  static double score(AnomalyDetector.Baseline baseline, int value) {
    double deviation = Math.max(baseline.deviation(), Math.sqrt(Math.max(baseline.mean(), 1)));
    return (value - baseline.mean()) / deviation;
  }

  private String message(
      Equipment equipment,
      String label,
      int value,
      AnomalyDetector.Baseline baseline,
      Instant start) {
    ZonedDateTime local = start.atZone(ZONE);
    return String.format(
        Locale.FRANCE,
        "Activité inhabituelle sur %s : %d %s entre %dh et %dh, contre %.1f en moyenne au même"
            + " créneau les jours précédents (%d jours comparés). Aucune règle n'a parlé : c'est"
            + " le volume qui sort de l'ordinaire.",
        equipment.getName(),
        value,
        label,
        local.getHour(),
        local.plusHours(1).getHour(),
        baseline.mean(),
        baseline.samples());
  }

  private Map<UUID, Integer> countsByEquipment(String kind, Instant start, Instant end) {
    var counts = new HashMap<UUID, Integer>();
    jdbc.query(
        "SELECT equipment_id, count(*) FROM security_event WHERE kind = ?"
            + " AND occurred_at >= ? AND occurred_at < ? GROUP BY equipment_id",
        rs -> {
          counts.put(rs.getObject(1, UUID.class), rs.getInt(2));
        },
        kind,
        Timestamp.from(start),
        Timestamp.from(end));
    return counts;
  }

  /**
   * Normale du créneau : le même intervalle d'une heure, les jours précédents. Les heures sans
   * aucun événement comptent pour zéro — sans quoi un serveur d'ordinaire silencieux n'aurait pour
   * historique que ses mauvais jours — mais seulement depuis le premier journal reçu de cet
   * équipement, pour ne pas inventer des zéros d'avant l'installation de l'agent.
   */
  private Optional<AnomalyDetector.Baseline> baseline(
      UUID equipmentId, String kind, Instant start) {
    Instant since = start.minus(history);
    Instant first =
        jdbc.queryForObject(
            "SELECT min(occurred_at) FROM security_event WHERE equipment_id = ?",
            (rs, i) -> {
              Timestamp value = rs.getTimestamp(1);
              return value == null ? null : value.toInstant();
            },
            equipmentId);
    if (first == null) {
      return Optional.empty();
    }
    // Regroupement par heure absolue (epoch / 3600) plutôt que par date_trunc : le résultat ne
    // dépend alors pas du fuseau de la session PostgreSQL.
    var past = new HashMap<Long, Integer>();
    jdbc.query(
        "SELECT floor(extract(epoch from occurred_at) / 3600)::bigint, count(*)"
            + " FROM security_event WHERE equipment_id = ? AND kind = ?"
            + " AND occurred_at >= ? AND occurred_at < ? GROUP BY 1",
        rs -> {
          past.put(rs.getLong(1), rs.getInt(2));
        },
        equipmentId,
        kind,
        Timestamp.from(since),
        Timestamp.from(start));
    var values = new ArrayList<Double>();
    for (long day = 1; day <= history.toDays(); day++) {
      Instant slot = start.atZone(ZONE).minusDays(day).toInstant();
      if (slot.isBefore(first) || slot.isBefore(since)) {
        break;
      }
      values.add((double) past.getOrDefault(slot.getEpochSecond() / 3600, 0));
    }
    return AnomalyDetector.baseline(values);
  }

  private static boolean notBlank(String value) {
    return value != null && !value.isBlank();
  }
}

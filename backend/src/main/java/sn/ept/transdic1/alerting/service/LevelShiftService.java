package sn.ept.transdic1.alerting.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;

/**
 * Rupture de niveau (M5 du plan, ADR 0038) : un indicateur qui a <b>changé de régime et y est
 * resté</b>. Le lien négocié à 100 Mb/s au lieu de 1 Gb/s après un remplacement de cordon, la
 * mémoire qui ne redescend plus depuis une mise à jour, le disque qui s'est mis à écrire deux fois
 * plus.
 *
 * <p><b>Le creux que cela comble.</b> La détection par point (ADR 0015) compare la valeur du moment
 * à l'habitude du même créneau : elle crie à l'instant du changement, puis se tait dès que le
 * nouveau niveau est devenu l'habitude. Une rupture arrivée un samedi soir est donc invisible le
 * lundi matin, alors que rien n'est revenu. Et un changement qui s'installe doucement ne franchit
 * jamais aucun seuil.
 *
 * <p>Chaque heure, pour chaque indicateur de chaque équipement, les quatorze derniers jours de
 * moyennes horaires passent au {@link LevelShift CUSUM}. Une rupture confirmée ouvre un
 * avertissement qui dit les deux niveaux et la date du changement.
 *
 * <p><b>Ce que l'alerte ne dit pas.</b> Elle constate que le niveau a changé et quand. Elle ne dit
 * pas pourquoi : une rupture peut être une panne comme une modification volontaire — et pour une
 * modification volontaire, cette alerte est justement la preuve qu'elle a bien pris effet.
 */
@Service
public class LevelShiftService {
  private static final Logger LOG = LoggerFactory.getLogger(LevelShiftService.class);
  private static final Set<AlertState> ACTIVE = Set.of(AlertState.OPEN, AlertState.IN_PROGRESS);
  private static final ZoneId ZONE = ZoneId.of("Africa/Dakar");
  private static final DateTimeFormatter WHEN =
      DateTimeFormatter.ofPattern("d MMMM 'à' HH'h'", Locale.FRANCE);

  /** Clé d'occurrence : une alerte à la fois par équipement et par indicateur. */
  static final String OCCURRENCE = "levelshift:";

  /**
   * Indicateurs écartés : ceux qui ne valent que 0 ou 100. Leur chute est déjà une alerte à elle
   * seule, et la dire une seconde fois en « rupture de niveau » ne renseignerait personne.
   */
  private static final List<String> EXCLUDED_PREFIXES =
      List.of("availability", "probe.up", "net.up", "service.up");

  private final JdbcTemplate jdbc;
  private final AlertRepository alerts;
  private final AlertEventRepository events;
  private final EquipmentRepository equipments;
  private final ApplicationEventPublisher publisher;
  private final JobGuard guard;
  private final Clock clock;
  private final boolean enabled;
  private final int days;
  private final int minHours;
  private final int maxPerEquipment;
  private final LevelShift.Settings settings;

  @SuppressWarnings("java:S107") // Les réglages sont nombreux parce qu'ils sont tous documentés.
  public LevelShiftService(
      JdbcTemplate jdbc,
      AlertRepository alerts,
      AlertEventRepository events,
      EquipmentRepository equipments,
      ApplicationEventPublisher publisher,
      JobGuard guard,
      Clock clock,
      @Value("${alerting.level-shift.enabled:true}") boolean enabled,
      @Value("${alerting.level-shift.days:14}") int days,
      @Value("${alerting.level-shift.min-hours:96}") int minHours,
      @Value("${alerting.level-shift.max-per-equipment:3}") int maxPerEquipment,
      @Value("${alerting.level-shift.drift:0.5}") double drift,
      @Value("${alerting.level-shift.limit:5.0}") double limit,
      @Value("${alerting.level-shift.min-after:6}") int minAfter,
      @Value("${alerting.level-shift.min-shift:2.0}") double minShift,
      @Value("${alerting.level-shift.min-relative:0.05}") double minRelative) {
    this.jdbc = jdbc;
    this.alerts = alerts;
    this.events = events;
    this.equipments = equipments;
    this.publisher = publisher;
    this.guard = guard;
    this.clock = clock;
    this.enabled = enabled;
    this.days = Math.max(3, days);
    this.minHours = Math.max(48, minHours);
    this.maxPerEquipment = Math.max(1, maxPerEquipment);
    this.settings =
        new LevelShift.Settings(
            // Bornes des réglages : en dessous, le CUSUM dérive sur du bruit ; au-dessus, il ne
            // voit plus qu'un changement énorme, ce que n'importe quel seuil verrait déjà.
            Math.min(2.0, Math.max(0.2, drift)),
            Math.min(12.0, Math.max(3.0, limit)),
            Math.max(3, minAfter),
            Math.max(1.0, minShift),
            Math.max(0.0, minRelative));
  }

  @Scheduled(cron = "${alerting.level-shift.cron:0 37 * * * *}", zone = "UTC")
  public void scheduledScan() {
    if (enabled) {
      guard.run("alerting.level-shift", Duration.ofMinutes(15), this::scan);
    }
  }

  /** Un passage sur tout le parc ; retourne le nombre d'alertes ouvertes. */
  @Transactional
  public int scan() {
    Instant now = clock.instant();
    int opened = 0;
    for (Equipment equipment : equipments.findAll()) {
      if (!equipment.isMonitoringEnabled()) {
        continue;
      }
      try {
        opened += examine(equipment, now);
      } catch (RuntimeException e) {
        // Un équipement aux données biscornues ne doit pas arrêter le passage.
        LOG.warn("Niveaux illisibles pour {} : {}", equipment.getId(), e.toString());
      }
    }
    return opened;
  }

  /** Une série de moyennes horaires, avec l'heure de chaque point. */
  public record Series(String key, String unit, List<Instant> hours, double[] values) {}

  /** Une rupture constatée sur un indicateur. */
  public record Shift(Series series, LevelShift.Result result) {

    /** Heure à laquelle le nouveau régime a commencé. */
    public Instant since() {
      return series.hours().get(result.onset());
    }
  }

  /**
   * Examine un équipement : toutes ses séries passent au CUSUM, les ruptures ouvrent une alerte et
   * celles qui ne sont plus là la referment.
   *
   * @return le nombre d'alertes ouvertes
   */
  public int examine(Equipment equipment, Instant now) {
    var shifts = new ArrayList<Shift>();
    var examined = new ArrayList<String>();
    for (Series series : series(equipment.getId(), now)) {
      examined.add(series.key());
      LevelShift.Result result = LevelShift.detect(series.values(), settings);
      if (result != null) {
        shifts.add(new Shift(series, result));
      }
    }
    // Un équipement refait à neuf verrait tous ses indicateurs changer d'un coup. On n'en ouvre
    // qu'un nombre borné, les plus francs d'abord : dix alertes pour un seul fait ne diraient pas
    // dix fois plus de choses.
    shifts.sort(
        Comparator.comparingDouble(
                (Shift shift) ->
                    Math.abs(shift.result().magnitude()) / Math.max(1e-9, shift.result().spread()))
            .reversed());
    int opened = 0;
    var shifted = new ArrayList<String>();
    for (Shift shift : shifts) {
      shifted.add(shift.series().key());
      if (opened >= maxPerEquipment) {
        continue;
      }
      if (open(equipment, shift, now)) {
        opened++;
      }
    }
    // Les séries examinées sans rupture, et dont une alerte reste ouverte, sont revenues à un
    // niveau stable : ou bien le régime d'avant est revenu, ou bien le nouveau a assez duré pour
    // devenir l'habitude. Le commentaire de clôture le dit sans trancher.
    for (String key : examined) {
      if (!shifted.contains(key)) {
        active(equipment.getId(), key).ifPresent(alert -> resolve(alert, now));
      }
    }
    return opened;
  }

  /**
   * Séries horaires de l'équipement sur la fenêtre, une par indicateur, de la plus ancienne heure à
   * la plus récente. Les heures sans mesure sont simplement absentes : combler les trous
   * reviendrait à inventer des points, et un trou décale l'indice sans fausser l'ordre.
   */
  public List<Series> series(UUID equipmentId, Instant now) {
    Instant from = now.minus(Duration.ofDays(days)).truncatedTo(ChronoUnit.HOURS);
    Instant to = now.truncatedTo(ChronoUnit.HOURS);
    var byKey = new LinkedHashMap<String, List<Instant>>();
    var valuesByKey = new LinkedHashMap<String, List<Double>>();
    var units = new LinkedHashMap<String, String>();
    jdbc.query(
        "SELECT metric_key, hour_start, avg_value, unit FROM metric_hourly"
            + " WHERE equipment_id = ? AND hour_start >= ? AND hour_start < ?"
            + " ORDER BY metric_key, hour_start",
        rs -> {
          String key = rs.getString(1);
          if (excluded(key)) {
            return;
          }
          byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(rs.getTimestamp(2).toInstant());
          valuesByKey
              .computeIfAbsent(key, k -> new ArrayList<>())
              .add(rs.getBigDecimal(3).doubleValue());
          units.putIfAbsent(key, rs.getString(4));
        },
        equipmentId,
        Timestamp.from(from),
        Timestamp.from(to));
    var result = new ArrayList<Series>();
    for (var entry : byKey.entrySet()) {
      List<Double> values = valuesByKey.get(entry.getKey());
      if (values.size() < minHours) {
        continue;
      }
      double[] array = new double[values.size()];
      for (int i = 0; i < array.length; i++) {
        array[i] = values.get(i);
      }
      result.add(
          new Series(
              entry.getKey(), units.getOrDefault(entry.getKey(), ""), entry.getValue(), array));
    }
    return result;
  }

  private static boolean excluded(String key) {
    return EXCLUDED_PREFIXES.stream().anyMatch(key::startsWith);
  }

  private Optional<Alert> active(UUID equipmentId, String key) {
    return alerts.findFirstByOccurrenceKeyStartingWithAndStateIn(
        OCCURRENCE + equipmentId + ":" + key + ":", ACTIVE);
  }

  /**
   * Vrai si une alerte porte déjà sur <b>cet</b> indicateur de cet équipement : une règle de seuil
   * posée sur lui, ou la détection par point qui l'a déjà signalé. La doubler d'une rupture
   * n'apprendrait rien à qui la regarde.
   *
   * <p>Les deux cas se reconnaissent différemment, et c'est voulu : une alerte de seuil tient son
   * indicateur de sa règle, tandis que la détection par point le met dans sa clé d'occurrence
   * ({@code anomaly:<équipement>:<indicateur>:}). Chercher la clé dans le message serait plus court
   * et attraperait n'importe quelle alerte qui se contente de la citer.
   */
  private boolean alreadyAlerted(UUID equipmentId, String key) {
    Integer found =
        jdbc.queryForObject(
            "SELECT count(*) FROM alert a LEFT JOIN alert_rule r ON r.id = a.rule_id"
                + " WHERE a.equipment_id = ? AND a.state IN ('OPEN','IN_PROGRESS')"
                + " AND (r.metric_type = ? OR a.occurrence_key LIKE ?)",
            Integer.class,
            equipmentId,
            key,
            "anomaly:" + equipmentId + ":" + key + ":%");
    return found != null && found > 0;
  }

  /** Ouvre l'alerte si elle n'existe pas déjà et si l'indicateur n'est pas déjà signalé. */
  private boolean open(Equipment equipment, Shift shift, Instant now) {
    String key = shift.series().key();
    if (active(equipment.getId(), key).isPresent() || alreadyAlerted(equipment.getId(), key)) {
      return false;
    }
    var alert =
        new Alert(
            equipment,
            OCCURRENCE + equipment.getId() + ":" + key + ":" + now.toEpochMilli(),
            AlertType.ANOMALY,
            AlertState.OPEN,
            Severity.WARNING,
            message(equipment, shift),
            now);
    alert.setMetricInstance(key);
    var event = new AlertEvent(alert, AlertEventType.OPENED, now);
    event.setNewState(AlertState.OPEN);
    alerts.save(alert);
    events.save(event);
    publisher.publishEvent(new AlertChangedEvent(alert.getId(), AlertEventType.OPENED));
    publisher.publishEvent(new AlertActivityEvent(alert.getId(), AlertEventType.OPENED));
    LOG.info(
        "Rupture de niveau sur {} / {} : {} puis {}",
        equipment.getName(),
        key,
        number(shift.result().before()),
        number(shift.result().after()));
    return true;
  }

  /**
   * Message de l'alerte : l'indicateur, les deux niveaux, la date du changement, et ce que la
   * méthode ne dit pas. La variation relative n'est donnée que si le niveau d'avant n'était pas nul
   * — « plus l'infini pour cent » n'aide personne.
   */
  String message(Equipment equipment, Shift shift) {
    LevelShift.Result result = shift.result();
    String unit = unit(shift.series().unit());
    String sense = result.rising() ? "est monté" : "est descendu";
    String relative =
        result.before() == 0
            ? ""
            : String.format(Locale.FRANCE, " (%+.0f %%)", result.relative() * 100);
    return "Rupture de niveau sur "
        + equipment.getName()
        + " : "
        + shift.series().key()
        + " "
        + sense
        + " de "
        + number(result.before())
        + unit
        + " à "
        + number(result.after())
        + unit
        + relative
        + " depuis le "
        + WHEN.format(shift.since().atZone(ZONE))
        + ", et s'y tient depuis. Le changement est durable, pas une pointe. La méthode dit que le"
        + " niveau a changé et quand, pas pourquoi : une rupture peut être une panne comme une"
        + " modification volontaire.";
  }

  /** Nombre lisible : un chiffre après la virgule, et aucun quand il ne sert à rien. */
  static String number(double value) {
    if (value == Math.rint(value) && Math.abs(value) < 1e9) {
      return String.format(Locale.FRANCE, "%.0f", value);
    }
    return String.format(Locale.FRANCE, "%.1f", value);
  }

  /** Unité collée au nombre pour un pourcentage, séparée sinon ; rien si elle est vide. */
  static String unit(String unit) {
    if (unit == null || unit.isBlank()) {
      return "";
    }
    return "%".equals(unit) ? " %" : " " + unit;
  }

  private void resolve(Alert alert, Instant now) {
    AlertState previous = alert.getState();
    alert.setState(AlertState.RESOLVED);
    alert.setResolvedAt(now);
    alert.setResolutionType(ResolutionType.AUTOMATIC);
    var event = new AlertEvent(alert, AlertEventType.RESOLVED, now);
    event.setPreviousState(previous);
    event.setNewState(AlertState.RESOLVED);
    event.setComment(
        "Niveau redevenu stable : soit celui d'avant est revenu, soit le nouveau a duré assez"
            + " longtemps pour devenir l'habitude.");
    alerts.save(alert);
    events.save(event);
    publisher.publishEvent(new AlertChangedEvent(alert.getId(), AlertEventType.RESOLVED));
    publisher.publishEvent(new AlertActivityEvent(alert.getId(), AlertEventType.RESOLVED));
  }
}

package sn.ept.transdic1.alerting.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;

/**
 * Anomalie à plusieurs mesures (M2 du plan, ADR 0037) : un équipement dont l'<b>état d'ensemble</b>
 * sort de l'ordinaire, alors qu'aucune de ses mesures prise à part ne dépasse sa normale.
 *
 * <p>La détection d'anomalies de l'ADR 0015 regarde chaque indicateur séparément. Elle ne voit pas
 * la machine peu chargée qui répond lentement, ni le lien au trafic habituel mais dont les erreurs
 * montent : chaque chiffre reste dans sa plage, c'est leur <i>combinaison</i> qui est inédite.
 *
 * <p>Une forêt d'isolement ({@link IsolationForest}) est ajustée chaque heure sur les quatorze
 * derniers jours de moyennes horaires de l'équipement, puis la dernière heure complète est notée.
 * Au-delà du seuil, un avertissement s'ouvre.
 *
 * <p><b>Ce que cette alerte ne dit pas.</b> Une forêt d'isolement ne nomme pas de cause : elle dit
 * « cet état est rare », pas pourquoi. Le message cite donc les indicateurs les plus éloignés de
 * leur habitude comme une piste, jamais comme une explication.
 */
@Service
public class MultivariateAnomalyService {
  private static final Logger LOG = LoggerFactory.getLogger(MultivariateAnomalyService.class);
  private static final Set<AlertState> ACTIVE = Set.of(AlertState.OPEN, AlertState.IN_PROGRESS);

  /** Clé d'occurrence : une alerte à la fois par équipement. */
  static final String OCCURRENCE = "anomaly:ensemble:";

  /** Graine fixe : le même historique donne toujours le même score. */
  static final long SEED = 20_261_123L;

  /**
   * Assiduité minimale d'un indicateur pour entrer dans le vecteur, rapportée au mieux mesuré : en
   * dessous, il coûterait plus d'heures complètes qu'il n'apporterait d'information.
   */
  static final double MIN_COVERAGE = 0.8;

  /**
   * Indicateurs écartés : ceux qui ne valent que 0 ou 100. Un drapeau de ce genre n'apporte rien à
   * un vecteur — sa chute est déjà une alerte à elle seule — et il ferait passer pour « rare »
   * toute heure où il bascule. La latence d'une sonde, elle, est gardée : une machine qui répond
   * lentement sans être chargée est exactement ce que cette détection cherche.
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
  private final int minFeatures;
  private final int maxFeatures;
  private final double minScore;
  private final double minDistance;
  private final double distanceMargin;

  public MultivariateAnomalyService(
      JdbcTemplate jdbc,
      AlertRepository alerts,
      AlertEventRepository events,
      EquipmentRepository equipments,
      ApplicationEventPublisher publisher,
      JobGuard guard,
      Clock clock,
      @Value("${alerting.multivariate.enabled:true}") boolean enabled,
      @Value("${alerting.multivariate.days:14}") int days,
      @Value("${alerting.multivariate.min-hours:72}") int minHours,
      @Value("${alerting.multivariate.min-features:3}") int minFeatures,
      @Value("${alerting.multivariate.max-features:8}") int maxFeatures,
      @Value("${alerting.multivariate.min-score:0.55}") double minScore,
      @Value("${alerting.multivariate.min-distance:4.0}") double minDistance,
      @Value("${alerting.multivariate.distance-margin:1.5}") double distanceMargin) {
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
    this.minFeatures = Math.max(2, minFeatures);
    this.maxFeatures = Math.max(minFeatures, maxFeatures);
    // Planchers absolus : sur une machine d'une régularité parfaite, la pire heure passée reste
    // un point ordinaire, et rien ne doit s'ouvrir pour si peu.
    this.minScore = Math.min(0.8, Math.max(0.5, minScore));
    this.minDistance = Math.max(3.0, minDistance);
    // Marge au-dessus du pire écart déjà vu : en deçà de 1,2, la moindre heure un peu à part
    // passerait pour une rupture.
    this.distanceMargin = Math.max(1.2, distanceMargin);
  }

  @Scheduled(cron = "${alerting.multivariate.cron:0 17 * * * *}", zone = "UTC")
  public void scheduledScan() {
    if (enabled) {
      guard.run("alerting.multivariate", Duration.ofMinutes(15), this::scan);
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
        LOG.warn("État d'ensemble illisible pour {} : {}", equipment.getId(), e.toString());
      }
    }
    return opened;
  }

  /** Ce qu'on a pu reconstituer de l'historique d'un équipement. */
  public record History(List<String> keys, double[][] rows, double[] latest, Instant latestHour) {}

  /**
   * Examine un équipement : ajuste la forêt sur son passé, note sa dernière heure complète.
   *
   * @return 1 si une alerte a été ouverte
   */
  public int examine(Equipment equipment, Instant now) {
    History history = history(equipment.getId(), now);
    Optional<Alert> active =
        alerts.findFirstByOccurrenceKeyStartingWithAndStateIn(
            OCCURRENCE + equipment.getId() + ":", ACTIVE);
    if (history == null) {
      return 0;
    }
    Verdict verdict = judge(history);
    if (verdict.unusual()) {
      if (active.isPresent()) {
        return 0; // déjà signalé : on ne répète pas
      }
      if (hasSingleMetricAlert(equipment.getId())) {
        // Une alerte porte déjà sur un indicateur de cette machine : la doubler d'un « état
        // inhabituel » n'apprendrait rien et ferait du bruit.
        return 0;
      }
      open(equipment, history, verdict, now);
      return 1;
    }
    if (active.isPresent() && verdict.clearlyBack()) {
      resolve(active.get(), now);
    }
    return 0;
  }

  /**
   * Ce que les deux méthodes disent de la dernière heure.
   *
   * @param isolated vrai si l'heure est plus isolée que toutes les heures passées
   * @param broken vrai si elle rompt les liens habituels entre indicateurs
   */
  public record Verdict(
      boolean isolated,
      boolean broken,
      double score,
      double scoreLimit,
      double distance,
      double distanceLimit) {

    public boolean unusual() {
      return isolated || broken;
    }

    /** Franchement revenu : les deux signaux nettement sous leur limite. */
    boolean clearlyBack() {
      return score < scoreLimit * 0.95 && distance < distanceLimit * 0.8;
    }
  }

  /**
   * Juge la dernière heure par deux méthodes complémentaires.
   *
   * <p>La <b>forêt d'isolement</b> repère ce qui est globalement rare. La <b>distance aux liens
   * habituels</b> repère ce qui rompt les corrélations, là où la forêt est faible : mesuré sur un
   * jeu d'épreuve, une machine peu chargée répondant lentement obtenait un score de forêt inférieur
   * au maximum des heures ordinaires — invisible — pour une distance de 24,7 contre un maximum
   * passé de 3,7.
   *
   * <p>Les deux seuils sont relatifs à la machine : aucune constante publiée ne vaut ici, parce que
   * l'échelle des scores dépend du nombre d'heures et de la dispersion du nuage. L'heure doit
   * dépasser <b>toutes</b> celles qui l'ont précédée ; avec trois cents heures d'historique, cela
   * suffit à rendre les fausses alertes rares, ce qui est le bon réglage pour une équipe de deux
   * personnes.
   */
  Verdict judge(History history) {
    double[][] past = new double[history.rows().length - 1][];
    System.arraycopy(history.rows(), 0, past, 0, past.length);
    // Les modèles sont ajustés sans la dernière heure : un point jugé par un modèle qui l'a vu
    // paraîtrait toujours ordinaire.
    IsolationForest forest =
        IsolationForest.fit(
            past, IsolationForest.DEFAULT_TREES, IsolationForest.DEFAULT_SAMPLE, SEED);
    double score = forest.score(history.latest());
    double worstScore = minScore;
    for (double[] row : past) {
      worstScore = Math.max(worstScore, forest.score(row));
    }

    CorrelationDistance correlation = CorrelationDistance.fit(past);
    double distance = 0;
    double worstDistance = minDistance;
    if (correlation != null) {
      distance = correlation.distance(history.latest());
      double worstPast = 0;
      for (double[] row : past) {
        worstPast = Math.max(worstPast, correlation.distance(row));
      }
      worstDistance = Math.max(minDistance, worstPast * distanceMargin);
    }
    return new Verdict(
        score >= worstScore,
        correlation != null && distance >= worstDistance,
        score,
        worstScore,
        distance,
        worstDistance);
  }

  /**
   * Moyennes horaires de la fenêtre, mises en tableau : une ligne par heure où <b>tous</b> les
   * indicateurs retenus ont une valeur. Une heure incomplète est écartée : compléter les trous
   * reviendrait à inventer des points et à les faire passer pour ordinaires.
   *
   * @return null si l'historique ne permet pas de juger
   */
  public History history(UUID equipmentId, Instant now) {
    Instant from = now.minus(Duration.ofDays(days)).truncatedTo(ChronoUnit.HOURS);
    Instant to = now.truncatedTo(ChronoUnit.HOURS);
    var byHour = new LinkedHashMap<Instant, Map<String, Double>>();
    var counts = new HashMap<String, Integer>();
    jdbc.query(
        "SELECT hour_start, metric_key, avg_value FROM metric_hourly"
            + " WHERE equipment_id = ? AND hour_start >= ? AND hour_start < ?"
            + " ORDER BY hour_start",
        rs -> {
          String key = rs.getString(2);
          if (excluded(key)) {
            return;
          }
          byHour
              .computeIfAbsent(rs.getTimestamp(1).toInstant(), hour -> new HashMap<>())
              .put(key, rs.getBigDecimal(3).doubleValue());
          counts.merge(key, 1, Integer::sum);
        },
        equipmentId,
        Timestamp.from(from),
        Timestamp.from(to));

    // Un indicateur relevé par épisodes ferait tomber toutes les heures où il manque : sur une
    // machine vue 240 heures, une colonne présente 11 fois ne laisserait que 11 heures complètes,
    // et l'équipement ne serait plus jugé du tout. Constaté sur l'instance de démonstration. On
    // ne garde donc que les indicateurs aussi assidus que le mieux mesuré d'entre eux, puis on
    // prend les meilleurs.
    int best = counts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
    int required = (int) Math.ceil(best * MIN_COVERAGE);
    List<String> keys =
        counts.entrySet().stream()
            .filter(entry -> entry.getValue() >= required)
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .limit(maxFeatures)
            .map(Map.Entry::getKey)
            .sorted()
            .toList();
    if (keys.size() < minFeatures) {
      return null;
    }
    var rows = new ArrayList<double[]>();
    Instant latestHour = null;
    for (var entry : byHour.entrySet()) {
      double[] row = new double[keys.size()];
      boolean complete = true;
      for (int i = 0; i < keys.size(); i++) {
        Double value = entry.getValue().get(keys.get(i));
        if (value == null) {
          complete = false;
          break;
        }
        row[i] = value;
      }
      if (complete) {
        rows.add(row);
        latestHour = entry.getKey();
      }
    }
    if (rows.size() < minHours) {
      return null;
    }
    return new History(keys, rows.toArray(double[][]::new), rows.get(rows.size() - 1), latestHour);
  }

  private static boolean excluded(String key) {
    return EXCLUDED_PREFIXES.stream().anyMatch(key::startsWith);
  }

  /** Vrai si une alerte porte déjà sur un indicateur de cet équipement. */
  private boolean hasSingleMetricAlert(UUID equipmentId) {
    Integer found =
        jdbc.queryForObject(
            "SELECT count(*) FROM alert WHERE equipment_id = ? AND state IN ('OPEN','IN_PROGRESS')"
                + " AND type IN ('THRESHOLD', 'ANOMALY') AND occurrence_key NOT LIKE ?",
            Integer.class,
            equipmentId,
            OCCURRENCE + "%");
    return found != null && found > 0;
  }

  private void open(Equipment equipment, History history, Verdict verdict, Instant now) {
    String message = message(equipment, history, verdict);
    var alert =
        new Alert(
            equipment,
            OCCURRENCE + equipment.getId() + ":" + now.toEpochMilli(),
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
    LOG.info(
        "État d'ensemble inhabituel sur {} (isolement {}, écart aux liens habituels {})",
        equipment.getName(),
        String.format(Locale.ROOT, "%.3f", verdict.score()),
        String.format(Locale.ROOT, "%.2f", verdict.distance()));
  }

  /**
   * Message de l'alerte : il dit ce qui est constaté — un état rare — et cite les indicateurs les
   * plus éloignés de leur habitude comme piste, en précisant que ce n'est pas une cause.
   */
  String message(Equipment equipment, History history, Verdict verdict) {
    // Écart de chaque indicateur à son habitude, en écarts médians : c'est ce qui sert à choisir
    // les trois à citer. Les nombres restent des nombres jusqu'au dernier moment ; les mettre en
    // forme pour les trier reviendrait à relire du français comme un décimal.
    record Deviation(String key, double value, double median, double distance) {}
    var deviations = new ArrayList<Deviation>();
    for (int i = 0; i < history.keys().size(); i++) {
      double[] column = column(history.rows(), i);
      double median = median(column);
      double spread = Math.max(0.5, 1.4826 * medianAbsoluteDeviation(column, median));
      deviations.add(
          new Deviation(
              history.keys().get(i),
              history.latest()[i],
              median,
              Math.abs(history.latest()[i] - median) / spread));
    }
    String cited =
        deviations.stream()
            .sorted(Comparator.comparingDouble(Deviation::distance).reversed())
            .limit(3)
            .map(
                deviation ->
                    String.format(
                        Locale.FRANCE,
                        "%s %.1f (habituellement %.1f)",
                        deviation.key(),
                        deviation.value(),
                        deviation.median()))
            .reduce((a, b) -> a + ", " + b)
            .orElse("");
    String constat =
        verdict.broken()
            ? "ses mesures ne vont plus ensemble comme d'habitude"
            : "la combinaison de ses mesures est plus inattendue que chacune des "
                + (history.rows().length - 1)
                + " heures observées";
    return "État inhabituel de "
        + equipment.getName()
        + " : "
        + constat
        + ", alors qu'aucune ne dépasse sa propre normale. Les plus éloignées de l'habitude : "
        + cited
        + ". C'est un constat, pas une cause : la méthode dit qu'un état est rare, pas pourquoi.";
  }

  private void resolve(Alert alert, Instant now) {
    AlertState previous = alert.getState();
    alert.setState(AlertState.RESOLVED);
    alert.setResolvedAt(now);
    alert.setResolutionType(ResolutionType.AUTOMATIC);
    var event = new AlertEvent(alert, AlertEventType.RESOLVED, now);
    event.setPreviousState(previous);
    event.setNewState(AlertState.RESOLVED);
    event.setComment("Retour à un état habituel");
    alerts.save(alert);
    events.save(event);
    publisher.publishEvent(new AlertChangedEvent(alert.getId(), AlertEventType.RESOLVED));
    publisher.publishEvent(new AlertActivityEvent(alert.getId(), AlertEventType.RESOLVED));
  }

  private static double[] column(double[][] rows, int index) {
    double[] column = new double[rows.length];
    for (int i = 0; i < rows.length; i++) {
      column[i] = rows[i][index];
    }
    return column;
  }

  static double median(double[] values) {
    double[] sorted = values.clone();
    java.util.Arrays.sort(sorted);
    int middle = sorted.length / 2;
    return sorted.length % 2 == 1 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2;
  }

  static double medianAbsoluteDeviation(double[] values, double median) {
    double[] distances = new double[values.length];
    for (int i = 0; i < values.length; i++) {
      distances[i] = Math.abs(values[i] - median);
    }
    return median(distances);
  }
}

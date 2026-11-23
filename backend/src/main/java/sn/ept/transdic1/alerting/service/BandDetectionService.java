package sn.ept.transdic1.alerting.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
import sn.ept.transdic1.inventory.repository.EquipmentRepository;

/**
 * Surveillance des taux réseau par bande de tolérance (M6 du plan, ADR 0043) : débit, erreurs,
 * rejets, gigue et perte, port par port.
 *
 * <p><b>Le creux que cela comble.</b> La détection d'anomalies de l'ADR 0015 compare une mesure à
 * l'habitude du même créneau sur vingt-huit jours, et il lui faut six moyennes horaires dans ce
 * créneau pour juger. Pour un port de commutateur fraîchement découvert, cela veut dire plusieurs
 * semaines sans surveillance — et un commutateur a quarante-huit ports. La bande exponentielle
 * ({@link ExponentialBand}) a une normale utilisable après une vingtaine de mesures, soit quelques
 * minutes, et son état tient en quatre nombres par indicateur.
 *
 * <p><b>Ce qu'elle ne remplace pas.</b> La bande s'adapte : un niveau qui change durablement
 * devient la nouvelle normale au bout de quelques heures. C'est voulu — un port dont le trafic
 * grandit avec l'usage ne doit pas crier tous les jours — mais cela veut dire qu'elle ne voit
 * <i>pas</i> les changements durables. C'est le travail du CUSUM (M5, ADR 0038), et les deux se
 * complètent sans se recouvrir.
 *
 * <p>Une seule mesure hors bande n'ouvre rien : il en faut plusieurs de suite. Et rien ne s'ouvre
 * si la détection par créneau a déjà une alerte sur le même indicateur — deux alertes pour un fait
 * ne disent pas deux fois plus de choses.
 */
@Service
public class BandDetectionService {
  private static final Logger LOG = LoggerFactory.getLogger(BandDetectionService.class);
  private static final Set<AlertState> ACTIVE = Set.of(AlertState.OPEN, AlertState.IN_PROGRESS);

  /** Clé d'occurrence : une alerte à la fois par équipement et par indicateur. */
  static final String OCCURRENCE = "band:";

  /**
   * Indicateurs surveillés : les taux réseau, port par port, et la qualité des sondes. Les autres —
   * processeur, mémoire, disque — ont peu d'instances par équipement et leur normale par créneau se
   * construit sans peine : la bande n'y apporterait rien et doublerait les alertes.
   */
  private static final List<String> WATCHED_PREFIXES =
      List.of("net.", "probe.jitter", "probe.loss");

  private final JdbcTemplate jdbc;
  private final AlertRepository alerts;
  private final AlertEventRepository events;
  private final EquipmentRepository equipments;
  private final ApplicationEventPublisher publisher;
  private final JobGuard guard;
  private final Clock clock;
  private final boolean enabled;
  private final double alpha;
  private final double width;
  private final int minConsecutive;
  private final int maxSamplesPerPass;

  @SuppressWarnings("java:S107") // Un réglage par décision, et chacune est documentée.
  public BandDetectionService(
      JdbcTemplate jdbc,
      AlertRepository alerts,
      AlertEventRepository events,
      EquipmentRepository equipments,
      ApplicationEventPublisher publisher,
      JobGuard guard,
      Clock clock,
      @Value("${alerting.band.enabled:true}") boolean enabled,
      @Value("${alerting.band.alpha:0.05}") double alpha,
      @Value("${alerting.band.width:6.0}") double width,
      @Value("${alerting.band.min-consecutive:3}") int minConsecutive,
      @Value("${alerting.band.max-samples:50000}") int maxSamplesPerPass) {
    this.jdbc = jdbc;
    this.alerts = alerts;
    this.events = events;
    this.equipments = equipments;
    this.publisher = publisher;
    this.guard = guard;
    this.clock = clock;
    this.enabled = enabled;
    this.alpha = Math.min(0.5, Math.max(0.001, alpha));
    // En dessous de quatre écarts, une bande exponentielle crie sur du bruit ordinaire.
    this.width = Math.min(12, Math.max(4, width));
    this.minConsecutive = Math.max(2, minConsecutive);
    this.maxSamplesPerPass = Math.max(1_000, maxSamplesPerPass);
  }

  @Scheduled(cron = "${alerting.band.cron:0 */2 * * * *}", zone = "UTC")
  public void scheduledScan() {
    if (enabled) {
      guard.run("alerting.band", Duration.ofMinutes(2), this::scan);
    }
  }

  /** Un passage : intègre les mesures nouvelles et ouvre ou referme ce qu'il faut. */
  @Transactional
  public int scan() {
    Instant now = clock.instant();
    Map<Key, ExponentialBand.State> bands = new LinkedHashMap<>();
    Map<Key, Integer> streaks = new LinkedHashMap<>();
    Map<Key, Instant> lastSample = new LinkedHashMap<>();
    load(bands, streaks, lastSample);

    int opened = 0;
    for (Sample sample : samples(now, lastSample)) {
      Key key = new Key(sample.equipmentId(), sample.key());
      ExponentialBand.State state = bands.get(key);
      int streak = streaks.getOrDefault(key, 0);
      boolean outside = state != null && state.outside(sample.value(), width);
      streak = outside ? Math.max(1, streak + 1) : Math.min(-1, streak - 1);
      if (outside && streak == minConsecutive && open(key, state, sample, now)) {
        opened++;
      }
      if (!outside && streak == -minConsecutive) {
        active(key).ifPresent(alert -> resolve(alert, now));
      }
      bands.put(key, ExponentialBand.update(state, sample.value(), alpha, width));
      streaks.put(key, streak);
      lastSample.put(key, sample.at());
    }
    save(bands, streaks, lastSample, now);
    return opened;
  }

  /** Un indicateur d'un équipement. */
  record Key(UUID equipmentId, String metricKey) {}

  /** Une mesure à intégrer. */
  record Sample(UUID equipmentId, String key, Instant at, double value, String unit) {}

  private void load(
      Map<Key, ExponentialBand.State> bands,
      Map<Key, Integer> streaks,
      Map<Key, Instant> lastSample) {
    jdbc.query(
        "SELECT equipment_id, metric_key, level, deviation, samples, streak, last_sample_at"
            + " FROM metric_band",
        (org.springframework.jdbc.core.RowCallbackHandler)
            rs -> {
              var key = new Key(rs.getObject(1, UUID.class), rs.getString(2));
              bands.put(
                  key, new ExponentialBand.State(rs.getDouble(3), rs.getDouble(4), rs.getInt(5)));
              streaks.put(key, rs.getInt(6));
              lastSample.put(key, rs.getTimestamp(7).toInstant());
            });
  }

  /**
   * Mesures arrivées depuis le dernier passage, dans l'ordre du temps. Le plafond protège d'une
   * collecte emballée ; au-delà, les plus anciennes sont prises d'abord et le reste au passage
   * suivant.
   */
  private List<Sample> samples(Instant now, Map<Key, Instant> lastSample) {
    Instant floor = now.minus(Duration.ofHours(1));
    var conditions = new ArrayList<String>();
    for (String prefix : WATCHED_PREFIXES) {
      conditions.add("m.metric_key LIKE '" + prefix.replace("'", "") + "%'");
    }
    return jdbc
        .query(
            "SELECT m.equipment_id, m.metric_key, m.collected_at, m.metric_value, m.unit"
                + " FROM metric m JOIN equipment e ON e.id = m.equipment_id"
                + " AND e.monitoring_enabled"
                + " WHERE m.collected_at >= ? AND m.quality <> 'BAD' AND m.service_id IS NULL"
                + " AND ("
                + String.join(" OR ", conditions)
                + ")"
                + " ORDER BY m.collected_at LIMIT ?",
            (rs, i) ->
                new Sample(
                    rs.getObject(1, UUID.class),
                    rs.getString(2),
                    rs.getTimestamp(3).toInstant(),
                    rs.getBigDecimal(4).doubleValue(),
                    rs.getString(5)),
            Timestamp.from(floor),
            maxSamplesPerPass)
        .stream()
        // Chaque mesure n'est intégrée qu'une fois : l'état retient jusqu'où il est allé.
        .filter(
            sample -> {
              Instant last = lastSample.get(new Key(sample.equipmentId(), sample.key()));
              return last == null || sample.at().isAfter(last);
            })
        .toList();
  }

  private void save(
      Map<Key, ExponentialBand.State> bands,
      Map<Key, Integer> streaks,
      Map<Key, Instant> lastSample,
      Instant now) {
    for (var entry : bands.entrySet()) {
      Key key = entry.getKey();
      ExponentialBand.State state = entry.getValue();
      jdbc.update(
          "INSERT INTO metric_band(equipment_id, metric_key, level, deviation, samples, streak,"
              + " last_sample_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
              + " ON CONFLICT (equipment_id, metric_key) DO UPDATE SET level = EXCLUDED.level,"
              + " deviation = EXCLUDED.deviation, samples = EXCLUDED.samples,"
              + " streak = EXCLUDED.streak, last_sample_at = EXCLUDED.last_sample_at,"
              + " updated_at = EXCLUDED.updated_at",
          key.equipmentId(),
          key.metricKey(),
          state.level(),
          state.deviation(),
          state.samples(),
          streaks.getOrDefault(key, 0),
          Timestamp.from(lastSample.getOrDefault(key, now)),
          Timestamp.from(now));
    }
  }

  private Optional<Alert> active(Key key) {
    return alerts.findFirstByOccurrenceKeyStartingWithAndStateIn(
        OCCURRENCE + key.equipmentId() + ":" + key.metricKey() + ":", ACTIVE);
  }

  /** Vrai si la détection par créneau a déjà une alerte sur cet indicateur. */
  private boolean alreadyAlerted(Key key) {
    Integer found =
        jdbc.queryForObject(
            "SELECT count(*) FROM alert WHERE equipment_id = ? AND state IN ('OPEN','IN_PROGRESS')"
                + " AND occurrence_key LIKE ?",
            Integer.class,
            key.equipmentId(),
            "anomaly:" + key.equipmentId() + ":" + key.metricKey() + ":%");
    return found != null && found > 0;
  }

  private boolean open(Key key, ExponentialBand.State state, Sample sample, Instant now) {
    if (active(key).isPresent() || alreadyAlerted(key)) {
      return false;
    }
    var equipment = equipments.findById(key.equipmentId()).orElse(null);
    if (equipment == null) {
      return false;
    }
    String unit = sample.unit() == null || sample.unit().isBlank() ? "" : " " + sample.unit();
    boolean above = sample.value() > state.level();
    String message =
        String.format(
            Locale.FRANCE,
            "%s %s de sa plage habituelle : %.1f%s depuis %d mesures, alors que ce port tient"
                + " d'ordinaire entre %.1f et %.1f%s. La plage est apprise sur les dernières"
                + " mesures, pas sur un seuil réglé à la main.",
            sample.key(),
            above ? "au-dessus" : "en dessous",
            sample.value(),
            unit,
            minConsecutive,
            state.low(width),
            state.high(width),
            unit);
    var alert =
        new Alert(
            equipment,
            OCCURRENCE + key.equipmentId() + ":" + key.metricKey() + ":" + now.toEpochMilli(),
            AlertType.ANOMALY,
            AlertState.OPEN,
            Severity.WARNING,
            message,
            now);
    alert.setMetricInstance(key.metricKey());
    var event = new AlertEvent(alert, AlertEventType.OPENED, now);
    event.setNewState(AlertState.OPEN);
    alerts.save(alert);
    events.save(event);
    publisher.publishEvent(new AlertChangedEvent(alert.getId(), AlertEventType.OPENED));
    publisher.publishEvent(new AlertActivityEvent(alert.getId(), AlertEventType.OPENED));
    LOG.info("Hors bande sur {} / {} : {}", equipment.getName(), key.metricKey(), sample.value());
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
    event.setComment("Revenu dans sa plage habituelle");
    alerts.save(alert);
    events.save(event);
    publisher.publishEvent(new AlertChangedEvent(alert.getId(), AlertEventType.RESOLVED));
    publisher.publishEvent(new AlertActivityEvent(alert.getId(), AlertEventType.RESOLVED));
  }

  /** Vrai si cet indicateur est surveillé par la bande. */
  static boolean watched(String metricKey) {
    return metricKey != null && WATCHED_PREFIXES.stream().anyMatch(metricKey::startsWith);
  }
}

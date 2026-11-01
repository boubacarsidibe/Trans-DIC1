package sn.ept.transdic1.monitoring.service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import sn.ept.transdic1.common.scope.SiteScope;
import sn.ept.transdic1.monitoring.dto.MetricHistoryResponse;
import sn.ept.transdic1.monitoring.dto.MetricHistoryResponse.Point;
import sn.ept.transdic1.monitoring.dto.MetricKeyResponse;

/**
 * Historique des mesures par équipement et par période. Les points sont agrégés par la base
 * (moyenne, minimum, maximum par intervalle) : le volume renvoyé reste borné quelle que soit la
 * durée demandée, et rien n'est chargé en mémoire au-delà des points tracés.
 */
@Service
public class MetricHistoryService {
  /** Durée maximale d'une requête et nombre maximal de points renvoyés. */
  static final Duration MAX_RANGE = Duration.ofDays(400);

  static final int MAX_POINTS = 5000;

  /** Les mesures brutes ne se demandent que sur de courtes périodes. */
  static final Duration MAX_RAW_RANGE = Duration.ofHours(6);

  /** Pas de regroupement disponibles ; AUTO choisit selon la durée demandée. */
  public enum Step {
    AUTO(null, null),
    RAW(null, Duration.ofSeconds(1)),
    MIN5("5 minutes", Duration.ofMinutes(5)),
    HOUR("1 hour", Duration.ofHours(1)),
    DAY("1 day", Duration.ofDays(1));

    private final String interval;
    private final Duration length;

    Step(String interval, Duration length) {
      this.interval = interval;
      this.length = length;
    }
  }

  private final JdbcTemplate jdbc;
  private final Clock clock;

  private final SiteScope scope;

  public MetricHistoryService(JdbcTemplate jdbc, Clock clock, SiteScope scope) {
    this.jdbc = jdbc;
    this.clock = clock;
    this.scope = scope;
  }

  @Transactional(readOnly = true)
  public MetricHistoryResponse history(
      UUID equipmentId, String key, Instant from, Instant to, Step requested) {
    requireEquipment(equipmentId);
    if (key == null || key.isBlank() || key.length() > 64) {
      throw bad("L'indicateur est obligatoire (64 caractères au plus)");
    }
    Instant end = to != null ? to : clock.instant();
    Instant start = from != null ? from : end.minus(Duration.ofHours(24));
    if (!start.isBefore(end)) {
      throw bad("La date de début doit précéder la date de fin");
    }
    Duration range = Duration.between(start, end);
    if (range.compareTo(MAX_RANGE) > 0) {
      throw bad("La période ne peut pas dépasser " + MAX_RANGE.toDays() + " jours");
    }
    Step step = requested == null || requested == Step.AUTO ? choose(range) : requested;
    if (step == Step.RAW) {
      if (range.compareTo(MAX_RAW_RANGE) > 0) {
        throw bad("Les mesures brutes sont limitées à " + MAX_RAW_RANGE.toHours() + " heures");
      }
    } else if (range.toMillis() / step.length.toMillis() > MAX_POINTS) {
      throw bad(
          "Trop de points pour ce pas : choisissez un pas plus large ou une période plus courte");
    }

    List<Point> points =
        step == Step.RAW
            ? raw(equipmentId, key, start, end)
            : grouped(equipmentId, key, start, end, step);
    String unit =
        jdbc
            .query(
                "SELECT unit FROM metric WHERE equipment_id = ? AND metric_key = ? AND collected_at"
                    + " >= ? AND collected_at < ? ORDER BY collected_at DESC LIMIT 1",
                (rs, i) -> rs.getString(1),
                equipmentId,
                key,
                Timestamp.from(start),
                Timestamp.from(end))
            .stream()
            .findFirst()
            .orElse("");
    return new MetricHistoryResponse(equipmentId, key, unit, start, end, step.name(), points);
  }

  /** Indicateurs vus sur l'équipement ces 30 derniers jours, avec leur dernière valeur. */
  @Transactional(readOnly = true)
  public List<MetricKeyResponse> keys(UUID equipmentId) {
    requireEquipment(equipmentId);
    Instant since = clock.instant().minus(Duration.ofDays(30));
    return jdbc.query(
        "SELECT DISTINCT ON (metric_key) metric_key, unit, metric_value, collected_at FROM metric"
            + " WHERE equipment_id = ? AND collected_at >= ? AND quality <> 'BAD'"
            + " ORDER BY metric_key, collected_at DESC",
        (rs, i) ->
            new MetricKeyResponse(
                rs.getString(1),
                rs.getString(2),
                rs.getBigDecimal(3),
                rs.getTimestamp(4).toInstant()),
        equipmentId,
        Timestamp.from(since));
  }

  private List<Point> raw(UUID equipmentId, String key, Instant start, Instant end) {
    return jdbc.query(
        "SELECT collected_at, metric_value FROM metric WHERE equipment_id = ? AND metric_key = ?"
            + " AND quality <> 'BAD' AND collected_at >= ? AND collected_at < ?"
            + " ORDER BY collected_at LIMIT "
            + (MAX_POINTS + 1),
        (rs, i) -> {
          BigDecimal value = rs.getBigDecimal(2);
          return new Point(rs.getTimestamp(1).toInstant(), value, value, value, 1);
        },
        equipmentId,
        key,
        Timestamp.from(start),
        Timestamp.from(end));
  }

  private List<Point> grouped(UUID equipmentId, String key, Instant start, Instant end, Step step) {
    // L'intervalle vient de l'énumération (jamais d'une saisie) : il peut être inséré dans le SQL.
    String sql =
        "SELECT date_bin(interval '"
            + step.interval
            + "', collected_at,"
            + " timestamptz '2000-01-01 00:00:00+00') AS bucket, avg(metric_value),"
            + " min(metric_value), max(metric_value), count(*) FROM metric"
            + " WHERE equipment_id = ? AND metric_key = ? AND quality <> 'BAD'"
            + " AND collected_at >= ? AND collected_at < ? GROUP BY bucket ORDER BY bucket";
    return jdbc.query(
        sql,
        (rs, i) ->
            new Point(
                rs.getTimestamp(1).toInstant(),
                rs.getBigDecimal(2).setScale(2, java.math.RoundingMode.HALF_UP),
                rs.getBigDecimal(3),
                rs.getBigDecimal(4),
                rs.getInt(5)),
        equipmentId,
        key,
        Timestamp.from(start),
        Timestamp.from(end));
  }

  /**
   * Pas adapté à la durée : de quoi tracer une courbe lisible sans dépasser quelques centaines de
   * points.
   */
  static Step choose(Duration range) {
    if (range.compareTo(Duration.ofHours(3)) <= 0) {
      return Step.RAW;
    }
    if (range.compareTo(Duration.ofDays(2)) <= 0) {
      return Step.MIN5;
    }
    if (range.compareTo(Duration.ofDays(60)) <= 0) {
      return Step.HOUR;
    }
    return Step.DAY;
  }

  private void requireEquipment(UUID equipmentId) {
    Integer found =
        jdbc.queryForObject(
            "SELECT count(*) FROM equipment WHERE id = ?", Integer.class, equipmentId);
    if (found == null || found == 0) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Équipement inconnu");
    }
    if (scope.restricted()) {
      UUID site =
          jdbc.queryForObject(
              "SELECT site_id FROM equipment WHERE id = ?", UUID.class, equipmentId);
      scope.requireVisible(site, "Équipement inconnu");
    }
  }

  private static ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }
}

package sn.ept.transdic1.monitoring.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Cycle de vie des mesures (ADR-0004) : agrégats horaires des mesures brutes, puis suppression des
 * partitions brutes expirées et des agrégats trop anciens. Une mesure n'est jamais supprimée avant
 * d'avoir été agrégée.
 */
@Service
public class MetricRetentionService {
  private static final Logger LOG = LoggerFactory.getLogger(MetricRetentionService.class);

  /** Les mesures peuvent arriver en retard : on recalcule les dernières heures déjà agrégées. */
  private static final Duration LATE_ARRIVAL_OVERLAP = Duration.ofHours(2);

  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final Duration rawRetention;
  private final int hourlyRetentionMonths;

  public MetricRetentionService(
      JdbcTemplate jdbc,
      Clock clock,
      @Value("${monitoring.metric.raw-retention-days:30}") long rawRetentionDays,
      @Value("${monitoring.metric.hourly-retention-months:13}") int hourlyRetentionMonths) {
    this.jdbc = jdbc;
    this.clock = clock;
    this.rawRetention = Duration.ofDays(rawRetentionDays);
    this.hourlyRetentionMonths = hourlyRetentionMonths;
  }

  /** Agrège par heure les mesures des heures terminées. Idempotent : peut être relancé. */
  @Scheduled(cron = "${monitoring.metric.aggregation-cron:0 5 * * * *}", zone = "UTC")
  public int aggregateCompletedHours() {
    Instant end = clock.instant().truncatedTo(ChronoUnit.HOURS);
    Instant start = aggregationStart();
    if (start == null || !start.isBefore(end)) {
      return 0;
    }
    int rows =
        jdbc.update(
            "INSERT INTO metric_hourly(equipment_id, metric_key, hour_start, unit, sample_count,"
                + " min_value, max_value, avg_value)"
                + " SELECT equipment_id, metric_key,"
                + " date_trunc('hour', collected_at AT TIME ZONE 'UTC') AT TIME ZONE 'UTC',"
                + " max(unit), count(*), min(metric_value), max(metric_value),"
                + " round(avg(metric_value), 4)"
                + " FROM metric WHERE collected_at >= ? AND collected_at < ?"
                + " GROUP BY equipment_id, metric_key,"
                + " date_trunc('hour', collected_at AT TIME ZONE 'UTC') AT TIME ZONE 'UTC'"
                + " ON CONFLICT (equipment_id, metric_key, hour_start) DO UPDATE SET"
                + " unit = EXCLUDED.unit, sample_count = EXCLUDED.sample_count,"
                + " min_value = EXCLUDED.min_value, max_value = EXCLUDED.max_value,"
                + " avg_value = EXCLUDED.avg_value",
            Timestamp.from(start),
            Timestamp.from(end));
    LOG.info("Agrégats horaires calculés : {} lignes entre {} et {}", rows, start, end);
    return rows;
  }

  /** Supprime les partitions brutes expirées déjà agrégées, et les agrégats trop anciens. */
  @Scheduled(cron = "${monitoring.metric.retention-cron:0 45 3 * * *}", zone = "UTC")
  public List<String> purgeExpired() {
    Instant now = clock.instant();
    List<String> dropped =
        jdbc.queryForList(
            "SELECT drop_expired_metric_partitions(?)",
            String.class,
            Timestamp.from(now.minus(rawRetention)));
    if (!dropped.isEmpty()) {
      LOG.info("Partitions de mesures brutes supprimées : {}", dropped);
    }
    Instant hourlyLimit = now.atZone(ZoneOffset.UTC).minusMonths(hourlyRetentionMonths).toInstant();
    int old =
        jdbc.update("DELETE FROM metric_hourly WHERE hour_start < ?", Timestamp.from(hourlyLimit));
    if (old > 0) {
      LOG.info("Agrégats horaires expirés supprimés : {}", old);
    }
    return dropped;
  }

  private Instant aggregationStart() {
    Timestamp watermark =
        jdbc.queryForObject("SELECT max(hour_start) FROM metric_hourly", Timestamp.class);
    if (watermark != null) {
      return watermark.toInstant().minus(LATE_ARRIVAL_OVERLAP);
    }
    Timestamp first = jdbc.queryForObject("SELECT min(collected_at) FROM metric", Timestamp.class);
    return first == null ? null : first.toInstant().truncatedTo(ChronoUnit.HOURS);
  }
}

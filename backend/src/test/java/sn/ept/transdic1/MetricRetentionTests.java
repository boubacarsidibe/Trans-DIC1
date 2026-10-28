package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.EquipmentStatus;
import sn.ept.transdic1.inventory.domain.EquipmentType;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.SiteRepository;
import sn.ept.transdic1.monitoring.domain.MeasurementQuality;
import sn.ept.transdic1.monitoring.domain.Metric;
import sn.ept.transdic1.monitoring.repository.MetricRepository;
import sn.ept.transdic1.monitoring.service.MetricPartitionService;
import sn.ept.transdic1.monitoring.service.MetricRetentionService;

/** Agrégats horaires et purge des partitions brutes (ADR-0004). */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MetricRetentionTests {
  @Autowired private JdbcTemplate jdbc;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private MetricRepository metrics;
  @Autowired private MetricRetentionService retention;
  @Autowired private MetricPartitionService partitions;

  private Equipment equipment;

  @BeforeEach
  void equipment() {
    jdbc.update("DELETE FROM metric_hourly");
    String unique = UUID.randomUUID().toString();
    Site site = sites.save(new Site("RET-" + unique.substring(0, 8), "Site de rétention"));
    equipment =
        equipments.save(
            new Equipment(
                "RET-EQ-" + unique,
                "Serveur de rétention",
                "10.247."
                    + (Math.floorMod(unique.hashCode(), 250) + 1)
                    + "."
                    + (Math.floorMod(unique.substring(9).hashCode(), 250) + 1),
                EquipmentType.SERVER,
                EquipmentStatus.UP,
                site));
    equipments.flush();
  }

  private void measure(String value, String when) {
    metrics.save(
        new Metric(
            equipment,
            "cpu.usage",
            new BigDecimal(value),
            "%",
            MeasurementQuality.GOOD,
            Instant.parse(when)));
    metrics.flush();
  }

  @Test
  void hourlyAggregateSummarisesTheMeasuresOfEachCompletedHour() {
    jdbc.queryForObject("SELECT ensure_metric_partition('2019-03-01')", String.class);
    measure("10", "2019-03-05T10:05:00Z");
    measure("30", "2019-03-05T10:35:00Z");
    measure("50", "2019-03-05T10:55:00Z");
    measure("70", "2019-03-05T11:10:00Z");

    retention.aggregateCompletedHours();

    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT sample_count, min_value, max_value, avg_value, unit FROM metric_hourly"
                + " WHERE equipment_id = ? ORDER BY hour_start",
            equipment.getId());
    assertThat(rows).hasSize(2);
    assertThat(rows.get(0)).containsEntry("sample_count", 3).containsEntry("unit", "%");
    assertThat((BigDecimal) rows.get(0).get("min_value")).isEqualByComparingTo("10");
    assertThat((BigDecimal) rows.get(0).get("max_value")).isEqualByComparingTo("50");
    assertThat((BigDecimal) rows.get(0).get("avg_value")).isEqualByComparingTo("30");
    assertThat(rows.get(1)).containsEntry("sample_count", 1);
  }

  @Test
  void aggregationCanBeRerunWithoutDuplicatingOrLosingData() {
    jdbc.queryForObject("SELECT ensure_metric_partition('2019-04-01')", String.class);
    measure("20", "2019-04-02T08:10:00Z");
    retention.aggregateCompletedHours();
    // Une mesure arrive en retard dans une heure déjà agrégée : l'agrégat est recalculé.
    measure("40", "2019-04-02T08:50:00Z");
    retention.aggregateCompletedHours();

    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT sample_count, avg_value FROM metric_hourly WHERE equipment_id = ?",
            equipment.getId());
    assertThat(row).containsEntry("sample_count", 2);
    assertThat((BigDecimal) row.get("avg_value")).isEqualByComparingTo("30");
  }

  @Test
  void anExpiredAndAggregatedPartitionIsDroppedAndItsHourlyHistoryKept() {
    // Il y a six mois : bien au-delà des 30 jours de mesures brutes, dans les 13 mois d'agrégats.
    YearMonth old = YearMonth.now(ZoneOffset.UTC).minusMonths(6);
    String partition = "metric_p" + old.format(DateTimeFormatter.ofPattern("yyyyMM"));
    jdbc.queryForObject(
        "SELECT ensure_metric_partition(?::date)", String.class, old.atDay(1).toString());
    jdbc.queryForObject(
        "SELECT ensure_metric_partition(?::date)",
        String.class,
        old.plusMonths(1).atDay(1).toString());
    measure("12", old.atDay(10) + "T09:00:00Z");
    // Une mesure du mois suivant montre que les agrégats ont dépassé la fin de la partition.
    measure("13", old.plusMonths(1).atDay(2) + "T00:30:00Z");
    retention.aggregateCompletedHours();

    List<String> dropped = retention.purgeExpired();

    assertThat(dropped).contains(partition);
    assertThat(jdbc.queryForObject("SELECT to_regclass(?) IS NULL", Boolean.class, partition))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM metric_hourly WHERE equipment_id = ?",
                Integer.class,
                equipment.getId()))
        .isEqualTo(2);
  }

  @Test
  void hourlyAggregatesOlderThanThirteenMonthsArePurged() {
    jdbc.queryForObject("SELECT ensure_metric_partition('2017-06-01')", String.class);
    measure("12", "2017-06-10T09:00:00Z");
    retention.aggregateCompletedHours();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM metric_hourly WHERE equipment_id = ?",
                Integer.class,
                equipment.getId()))
        .isEqualTo(1);

    retention.purgeExpired();

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM metric_hourly WHERE equipment_id = ?",
                Integer.class,
                equipment.getId()))
        .isZero();
  }

  @Test
  void aPartitionNotYetAggregatedOrNotYetExpiredIsKept() {
    List<String> upcoming = partitions.ensureUpcomingPartitions();
    String currentMonth = upcoming.get(0);
    String future = upcoming.get(upcoming.size() - 1);
    measure("5", Instant.now().toString());
    retention.aggregateCompletedHours();

    // Coupure très tardive : seule la date d'agrégation protège encore les mois à venir.
    List<String> dropped =
        jdbc.queryForList(
            "SELECT drop_expired_metric_partitions('2100-01-01T00:00:00Z')", String.class);
    List<String> droppedWithNormalCutoff = retention.purgeExpired();

    assertThat(dropped).doesNotContain(currentMonth, future);
    assertThat(droppedWithNormalCutoff).doesNotContain(currentMonth, future);
    assertThat(jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, future))
        .isTrue();
  }

  @Test
  void nothingIsDroppedBeforeAnyAggregateExists() {
    jdbc.update("DELETE FROM metric_hourly");
    jdbc.queryForObject("SELECT ensure_metric_partition('2016-02-01')", String.class);

    List<String> dropped =
        jdbc.queryForList(
            "SELECT drop_expired_metric_partitions('2100-01-01T00:00:00Z')", String.class);

    assertThat(dropped).isEmpty();
    assertThat(
            jdbc.queryForObject("SELECT to_regclass('metric_p201602') IS NOT NULL", Boolean.class))
        .isTrue();
  }
}

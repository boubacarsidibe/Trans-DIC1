package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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

/** Vérifie la table metric partitionnée par mois (ADR-0004). */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MetricPartitioningTests {
  @Autowired private JdbcTemplate jdbc;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private MetricRepository metrics;
  @Autowired private MetricPartitionService partitions;

  private Equipment equipment() {
    String unique = UUID.randomUUID().toString();
    Site site = sites.save(new Site("PART-" + unique.substring(0, 8), "Site de partition"));
    return equipments.save(
        new Equipment(
            "PART-EQ-" + unique,
            "Serveur de partition",
            "10.248."
                + (Math.floorMod(unique.hashCode(), 250) + 1)
                + "."
                + (Math.floorMod(unique.substring(9).hashCode(), 250) + 1),
            EquipmentType.SERVER,
            EquipmentStatus.UP,
            site));
  }

  @Test
  void metricIsAPartitionedTable() {
    String kind =
        jdbc.queryForObject(
            "SELECT relkind::text FROM pg_class WHERE oid = 'metric'::regclass", String.class);
    assertThat(kind).isEqualTo("p");
  }

  @Test
  void primaryKeyContainsThePartitionKey() {
    List<String> columns =
        jdbc.queryForList(
            "SELECT a.attname FROM pg_index i JOIN pg_attribute a ON a.attrelid = i.indrelid"
                + " AND a.attnum = ANY(i.indkey) WHERE i.indrelid = 'metric'::regclass"
                + " AND i.indisprimary ORDER BY a.attname",
            String.class);
    assertThat(columns).containsExactly("collected_at", "id");
  }

  @Test
  void currentAndUpcomingMonthsHavePartitions() {
    List<String> created = partitions.ensureUpcomingPartitions();

    assertThat(created).hasSize(4);
    Integer attached =
        jdbc.queryForObject(
            "SELECT count(*) FROM pg_inherits WHERE inhparent = 'metric'::regclass"
                + " AND inhrelid::regclass::text = ANY(?)",
            Integer.class,
            (Object) created.toArray(new String[0]));
    assertThat(attached).isEqualTo(4);
    // Idempotent : un second passage ne crée rien et ne lève pas d'erreur.
    assertThat(partitions.ensureUpcomingPartitions()).isEqualTo(created);
  }

  @Test
  void jpaInsertsLandInTheMonthlyPartitionWithAGeneratedId() {
    partitions.ensureUpcomingPartitions();
    Metric saved =
        metrics.save(
            new Metric(
                equipment(),
                "cpu.usage",
                new BigDecimal("12.5"),
                "%",
                MeasurementQuality.GOOD,
                Instant.now()));
    metrics.flush();

    assertThat(saved.getId()).isNotNull().isPositive();
    String partition =
        jdbc.queryForObject(
            "SELECT tableoid::regclass::text FROM metric WHERE id = ?",
            String.class,
            saved.getId());
    assertThat(partition).startsWith("metric_p").isNotEqualTo("metric_default");
  }

  @Test
  void aMeasureOutsideEveryMonthlyPartitionGoesToTheDefaultPartition() {
    Metric saved =
        metrics.save(
            new Metric(
                equipment(),
                "cpu.usage",
                new BigDecimal("1"),
                "%",
                MeasurementQuality.GOOD,
                Instant.parse("2001-01-15T00:00:00Z")));
    metrics.flush();

    String partition =
        jdbc.queryForObject(
            "SELECT tableoid::regclass::text FROM metric WHERE id = ?",
            String.class,
            saved.getId());
    assertThat(partition).isEqualTo("metric_default");
  }
}

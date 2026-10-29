package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import sn.ept.transdic1.collection.dto.MeasureInput;
import sn.ept.transdic1.collection.service.SimulatedCollectionService;
import sn.ept.transdic1.collection.service.SimulatedMetricSource;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.EquipmentStatus;
import sn.ept.transdic1.inventory.domain.EquipmentType;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.SiteRepository;
import sn.ept.transdic1.monitoring.repository.MetricRepository;

/** Source simulée : valeurs réalistes et reproductibles, et cycle de collecte sans attente. */
@SpringBootTest(properties = "collection.simulated.enabled=true")
@ActiveProfiles("test")
class SimulatedCollectionTests {
  @Autowired private SimulatedMetricSource source;
  @Autowired private SimulatedCollectionService service;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private MetricRepository metrics;
  @Autowired private JdbcTemplate jdbc;

  private Site site;
  private Equipment equipment;
  private List<UUID> pausedByTheTest;

  @BeforeEach
  void data() {
    // Seul l'équipement de ce test est lu : les équipements de démonstration restent intacts.
    pausedByTheTest =
        jdbc.queryForList("SELECT id FROM equipment WHERE monitoring_enabled", UUID.class);
    jdbc.update("UPDATE equipment SET monitoring_enabled = FALSE");
    String unique = UUID.randomUUID().toString();
    site = sites.save(new Site("SIM-" + unique.substring(0, 8), "Site simulé"));
    equipment =
        equipments.save(
            new Equipment(
                "SIM-EQ-" + unique,
                "Serveur simulé",
                "10.245."
                    + (Math.floorMod(unique.hashCode(), 250) + 1)
                    + "."
                    + (Math.floorMod(unique.substring(9).hashCode(), 250) + 1),
                EquipmentType.SERVER,
                EquipmentStatus.UP,
                site));
  }

  @AfterEach
  void cleanUp() {
    jdbc.update("DELETE FROM metric WHERE equipment_id = ?", equipment.getId());
    jdbc.update("DELETE FROM equipment WHERE id = ?", equipment.getId());
    jdbc.update("DELETE FROM site WHERE id = ?", site.getId());
    pausedByTheTest.forEach(
        id -> jdbc.update("UPDATE equipment SET monitoring_enabled = TRUE WHERE id = ?", id));
  }

  @Test
  void producesTheFourIndicatorsInRangeWithUtcSecondTimestamps() {
    Instant at = Instant.parse("2026-10-28T10:15:30.789Z");

    List<MeasureInput> measures = source.read(equipment, at);

    assertThat(measures)
        .extracting(MeasureInput::key)
        .containsExactly("cpu.usage", "memory.usage", "disk.usage", "availability");
    assertThat(measures)
        .allSatisfy(
            m -> {
              assertThat(m.value()).isBetween(BigDecimal.ZERO, BigDecimal.valueOf(100));
              assertThat(m.unit()).isEqualTo("%");
              assertThat(m.collectedAt()).isEqualTo(Instant.parse("2026-10-28T10:15:30Z"));
              assertThat(m.collectedAt().atOffset(ZoneOffset.UTC).getNano()).isZero();
            });
  }

  @Test
  void isReproducibleForTheSameEquipmentAndInstant() {
    Instant at = Instant.parse("2026-10-28T10:15:30Z");

    assertThat(source.read(equipment, at)).isEqualTo(source.read(equipment, at));
  }

  @Test
  void varyingOverTimeAndAcrossEquipmentsStaysWithinBounds() {
    Instant start = Instant.parse("2026-10-28T00:00:00Z");
    var cpu =
        java.util.stream.IntStream.range(0, 24 * 60)
            .mapToObj(i -> source.read(equipment, start.plusSeconds(60L * i)).get(0).value())
            .toList();

    assertThat(cpu)
        .allSatisfy(v -> assertThat(v).isBetween(BigDecimal.ZERO, BigDecimal.valueOf(100)));
    assertThat(cpu.stream().distinct().count()).isGreaterThan(100);
    assertThat(cpu.stream().max(BigDecimal::compareTo).orElseThrow())
        .isGreaterThan(cpu.stream().min(BigDecimal::compareTo).orElseThrow().add(BigDecimal.TEN));
  }

  @Test
  void aCollectionRoundPersistsMeasuresForMonitoredEquipments() {
    int read = service.collectOnce();

    assertThat(read).isGreaterThanOrEqualTo(1);
    assertThat(
            metrics.findFirstByEquipmentIdAndKeyOrderByCollectedAtDesc(
                equipment.getId(), "cpu.usage"))
        .isPresent();
    assertThat(
            metrics.findFirstByEquipmentIdAndKeyOrderByCollectedAtDesc(
                equipment.getId(), "availability"))
        .isPresent();
  }

  @Test
  void skipsEquipmentsWhoseMonitoringIsDisabled() {
    equipment.setMonitoringEnabled(false);
    equipments.save(equipment);

    service.collectOnce();

    assertThat(
            metrics.findFirstByEquipmentIdAndKeyOrderByCollectedAtDesc(
                equipment.getId(), "cpu.usage"))
        .isEmpty();
  }
}

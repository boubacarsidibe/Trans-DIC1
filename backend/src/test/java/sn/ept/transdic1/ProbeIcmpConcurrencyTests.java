package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import sn.ept.transdic1.collection.probe.ProbeResult;
import sn.ept.transdic1.collection.service.MeasureIngestionService;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.EquipmentStatus;
import sn.ept.transdic1.inventory.domain.EquipmentType;
import sn.ept.transdic1.inventory.domain.MonitoredService;
import sn.ept.transdic1.inventory.domain.ServiceType;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.MonitoredServiceRepository;
import sn.ept.transdic1.inventory.repository.SiteRepository;

/**
 * Deux sondes ICMP d'un même équipement qui reprennent en même temps, chacune dans sa transaction
 * comme dans ProbeService : la seconde attend la première et voit son compteur validé, donc
 * l'équipement repasse en service et l'alerte se résout une seule fois. Pas de transaction de test
 * : les données validées sont retirées à la main.
 */
@SpringBootTest
@ActiveProfiles("test")
class ProbeIcmpConcurrencyTests {
  private static final int ROUNDS = 5;

  @Autowired private MeasureIngestionService ingestion;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private MonitoredServiceRepository services;
  @Autowired private TransactionTemplate transaction;
  @Autowired private JdbcTemplate jdbc;

  private final List<UUID> createdEquipments = new ArrayList<>();
  private final List<UUID> createdSites = new ArrayList<>();

  @AfterEach
  void cleanUp() {
    for (UUID id : createdEquipments) {
      String alertsOf = "SELECT id FROM alert WHERE equipment_id = ?";
      jdbc.update("DELETE FROM notification WHERE alert_id IN (" + alertsOf + ")", id);
      jdbc.update("DELETE FROM alert_assignment WHERE alert_id IN (" + alertsOf + ")", id);
      jdbc.update("DELETE FROM alert WHERE equipment_id = ?", id);
      jdbc.update("DELETE FROM metric WHERE equipment_id = ?", id);
      jdbc.update("DELETE FROM monitored_service WHERE equipment_id = ?", id);
      jdbc.update("DELETE FROM equipment WHERE id = ?", id);
    }
    for (UUID id : createdSites) {
      jdbc.update("DELETE FROM site WHERE id = ?", id);
    }
  }

  @Test
  void twoPingsRecoveringTogetherBringTheEquipmentBackOnce() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      for (int round = 0; round < ROUNDS; round++) {
        runRound(pool);
      }
    } finally {
      pool.shutdownNow();
    }
  }

  private void runRound(ExecutorService pool) throws Exception {
    String unique = UUID.randomUUID().toString();
    Site site = sites.save(new Site("PIC-" + unique.substring(0, 8), "Site concurrent"));
    createdSites.add(site.getId());
    Equipment equipment =
        equipments.save(
            new Equipment(
                "PIC-EQ-" + unique,
                "Serveur concurrent",
                "10.244."
                    + (Math.floorMod(unique.hashCode(), 250) + 1)
                    + "."
                    + (Math.floorMod(UUID.randomUUID().hashCode(), 250) + 1),
                EquipmentType.SERVER,
                EquipmentStatus.UP,
                site));
    createdEquipments.add(equipment.getId());
    UUID a = services.save(new MonitoredService(equipment, "Ping A", ServiceType.ICMP)).getId();
    UUID b = services.save(new MonitoredService(equipment, "Ping B", ServiceType.ICMP)).getId();

    Instant base = Instant.now().truncatedTo(ChronoUnit.SECONDS).minus(10, ChronoUnit.MINUTES);
    for (int i = 0; i < 3; i++) {
      Instant at = base.plus(i, ChronoUnit.MINUTES);
      ingestion.ingestProbe(a, ProbeResult.down("Délai dépassé"), at);
      ingestion.ingestProbe(b, ProbeResult.down("Délai dépassé"), at);
    }
    assertThat(status(equipment)).isEqualTo("DOWN");

    Instant recovery = base.plus(5, ChronoUnit.MINUTES);
    CyclicBarrier start = new CyclicBarrier(2);
    List<Future<?>> runs = new ArrayList<>();
    for (UUID probe : List.of(a, b)) {
      runs.add(
          pool.submit(
              () -> {
                start.await(10, TimeUnit.SECONDS);
                return transaction.execute(
                    status -> ingestion.ingestProbe(probe, ProbeResult.up(3), recovery));
              }));
    }
    for (Future<?> run : runs) {
      run.get(30, TimeUnit.SECONDS);
    }

    assertThat(status(equipment)).isEqualTo("UP");
    assertThat(
            jdbc.queryForList(
                "SELECT state FROM alert WHERE equipment_id = ? AND type = 'COLLECTION'",
                String.class,
                equipment.getId()))
        .containsExactly("RESOLVED");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM alert_event e JOIN alert a ON a.id = e.alert_id"
                    + " WHERE a.equipment_id = ? AND a.type = 'COLLECTION' AND e.type = 'RESOLVED'",
                Long.class,
                equipment.getId()))
        .isEqualTo(1);
  }

  private String status(Equipment equipment) {
    return jdbc.queryForObject(
        "SELECT operational_status FROM equipment WHERE id = ?", String.class, equipment.getId());
  }
}

package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.alerting.service.AnomalyDetectionService;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.EquipmentStatus;
import sn.ept.transdic1.inventory.domain.EquipmentType;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.SiteRepository;

/**
 * Détection d'anomalies sur une vraie base : ouverture expliquée, pas de doublon, retour au calme.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AnomalyDetectionTests {
  @Autowired private AnomalyDetectionService anomalies;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private jakarta.persistence.EntityManager em;

  private Equipment server;

  @BeforeEach
  void data() {
    String unique = UUID.randomUUID().toString();
    Site site = sites.save(new Site("ANO-" + unique.substring(0, 8), "Site anomalies"));
    server =
        equipments.save(
            new Equipment(
                "ANO-EQ-" + unique,
                "Serveur suivi",
                "10.230."
                    + (Math.floorMod(unique.hashCode(), 250) + 1)
                    + "."
                    + (Math.floorMod(unique.substring(9).hashCode(), 250) + 1),
                EquipmentType.SERVER,
                EquipmentStatus.UP,
                site));
    equipments.flush();
    // 28 jours d'historique horaire : charge habituelle autour de 30 % (± 2).
    Instant hour = Instant.now().truncatedTo(ChronoUnit.HOURS);
    for (int h = 2; h <= 28 * 24; h++) {
      double value = 30 + (h % 5) - 2;
      jdbc.update(
          "INSERT INTO metric_hourly(equipment_id, metric_key, hour_start, unit, sample_count, min_value,"
              + " max_value, avg_value) VALUES (?, 'cpu.usage', ?, '%', 12, ?, ?, ?)",
          server.getId(),
          Timestamp.from(hour.minus(Duration.ofHours(h))),
          value - 1,
          value + 1,
          value);
    }
  }

  @AfterEach
  void reset() {
    anomalies.setSensitivity(3.5);
  }

  private void measure(double value) {
    jdbc.update(
        "INSERT INTO metric(equipment_id, metric_key, metric_value, unit, quality, collected_at, received_at)"
            + " VALUES (?, 'cpu.usage', ?, '%', 'GOOD', ?, ?)",
        server.getId(),
        value,
        Timestamp.from(Instant.now().minusSeconds(60)),
        Timestamp.from(Instant.now()));
  }

  private int active() {
    em.flush();
    return jdbc.queryForObject(
        "SELECT count(*) FROM alert WHERE equipment_id = ? AND type = 'ANOMALY' AND state = 'OPEN'",
        Integer.class,
        server.getId());
  }

  @Test
  void unEcartInhabituelOuvreUneAlerteAnomalieExpliqueeEtUneSeule() {
    measure(95);

    anomalies.scan();
    anomalies.scan();

    assertThat(active()).isEqualTo(1);
    String message =
        jdbc.queryForObject(
            "SELECT message FROM alert WHERE equipment_id = ? AND type = 'ANOMALY'",
            String.class,
            server.getId());
    assertThat(message)
        .startsWith("Comportement inhabituel : cpu.usage = 95.0 %")
        .contains("normale")
        .contains("écarts-types");
  }

  @Test
  void uneValeurHabituelleNOuvreRienEtLeRetourAuCalmeResoutLAlerte() {
    measure(31);
    anomalies.scan();
    assertThat(active()).isZero();

    jdbc.update("DELETE FROM metric WHERE equipment_id = ?", server.getId());
    measure(95);
    anomalies.scan();
    assertThat(active()).isEqualTo(1);

    jdbc.update("DELETE FROM metric WHERE equipment_id = ?", server.getId());
    measure(30);
    anomalies.scan();
    assertThat(active()).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT resolution_type FROM alert WHERE equipment_id = ? AND type = 'ANOMALY'",
                String.class,
                server.getId()))
        .isEqualTo("AUTOMATIC");
  }

  @Test
  void laSensibiliteSeRegle() {
    measure(36);
    anomalies.setSensitivity(10);
    anomalies.scan();
    assertThat(active()).isZero();

    anomalies.setSensitivity(1.5);
    anomalies.scan();
    assertThat(active()).isEqualTo(1);
  }
}

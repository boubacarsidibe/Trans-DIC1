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
import sn.ept.transdic1.inventory.domain.MonitoredService;
import sn.ept.transdic1.inventory.domain.ServiceType;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.MonitoredServiceRepository;
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
  @Autowired private MonitoredServiceRepository services;
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
  void lesMesuresDeSondeNOuvrentPasDAnomalie() {
    var service = services.saveAndFlush(new MonitoredService(server, "Ping", ServiceType.ICMP));
    // Plus de 3 jours de sonde toujours joignable (probe.up = 100), puis un seul échec.
    Instant hour = Instant.now().truncatedTo(ChronoUnit.HOURS);
    for (int h = 2; h <= 4 * 24; h++) {
      jdbc.update(
          "INSERT INTO metric_hourly(equipment_id, metric_key, hour_start, unit, sample_count, min_value,"
              + " max_value, avg_value) VALUES (?, 'probe.up', ?, '%', 60, 100, 100, 100)",
          server.getId(), Timestamp.from(hour.minus(Duration.ofHours(h))));
    }
    jdbc.update(
        "INSERT INTO metric(equipment_id, service_id, metric_key, metric_value, unit, quality,"
            + " collected_at, received_at) VALUES (?, ?, 'probe.up', 0, '%', 'GOOD', ?, ?)",
        server.getId(),
        service.getId(),
        Timestamp.from(Instant.now().minusSeconds(60)),
        Timestamp.from(Instant.now()));

    anomalies.scan();

    assertThat(active()).isZero();
  }

  /** 10 jours de latence habituelle (autour de 20 ms), une mesure toutes les 20 minutes. */
  private MonitoredService probeWithHistory() {
    var service = services.saveAndFlush(new MonitoredService(server, "Portail", ServiceType.HTTPS));
    Instant hour = Instant.now().truncatedTo(ChronoUnit.HOURS);
    for (int h = 2; h <= 10 * 24; h++) {
      for (int m = 0; m < 60; m += 20) {
        Instant at = hour.minus(Duration.ofHours(h)).plus(Duration.ofMinutes(m));
        latency(service, 20 + (h + m) % 3, at);
      }
    }
    return service;
  }

  private void latency(MonitoredService service, double value, Instant at) {
    jdbc.update(
        "INSERT INTO metric(equipment_id, service_id, metric_key, metric_value, unit, quality,"
            + " collected_at, received_at) VALUES (?, ?, 'probe.latency', ?, 'ms', 'GOOD', ?, ?)",
        server.getId(),
        service.getId(),
        value,
        Timestamp.from(at),
        Timestamp.from(at));
  }

  private void recentLatency(MonitoredService service, double value) {
    for (int minutes = 1; minutes <= 3; minutes++) {
      latency(service, value, Instant.now().minus(Duration.ofMinutes(minutes)));
    }
  }

  @Test
  void unServiceNettementPlusLentOuvreUneAlerteRattacheeAuServicePuisSeResout() {
    var service = probeWithHistory();
    recentLatency(service, 180);

    anomalies.scan();
    anomalies.scan();

    assertThat(active()).isEqualTo(1);
    var row =
        jdbc.queryForMap(
            "SELECT message, service_id FROM alert WHERE equipment_id = ? AND type = 'ANOMALY'",
            server.getId());
    assertThat((String) row.get("message"))
        .startsWith("Service plus lent que d'habitude : Portail répond en 180.0 ms")
        .contains("normale");
    assertThat(row.get("service_id")).isEqualTo(service.getId());

    jdbc.update(
        "DELETE FROM metric WHERE service_id = ? AND collected_at >= ?",
        service.getId(),
        Timestamp.from(Instant.now().minus(Duration.ofMinutes(15))));
    recentLatency(service, 21);
    anomalies.scan();
    assertThat(active()).isZero();
  }

  @Test
  void unServicePlusRapideOuAPeinePlusLentNOuvreRien() {
    var service = probeWithHistory();
    // Plus rapide que d'habitude : ce n'est pas un problème.
    recentLatency(service, 2);
    anomalies.scan();
    assertThat(active()).isZero();

    jdbc.update(
        "DELETE FROM metric WHERE service_id = ? AND collected_at >= ?",
        service.getId(),
        Timestamp.from(Instant.now().minus(Duration.ofMinutes(15))));
    // 4 ms de plus : beaucoup d'écarts-types sur une série très régulière, mais sans gravité.
    recentLatency(service, 25);
    anomalies.scan();
    assertThat(active()).isZero();
  }

  @Test
  void uneSeuleMesureLenteNeSuffitPas() {
    var service = probeWithHistory();
    latency(service, 500, Instant.now().minus(Duration.ofMinutes(1)));

    anomalies.scan();

    assertThat(active()).isZero();
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

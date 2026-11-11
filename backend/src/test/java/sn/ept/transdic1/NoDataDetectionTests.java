package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.alerting.service.NoDataDetectionService;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.EquipmentStatus;
import sn.ept.transdic1.inventory.domain.EquipmentType;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.SiteRepository;

/**
 * Absence de donnée : un indicateur régulier qui se tait ouvre une alerte, sauf si l'équipement est
 * déjà muet pour tout. Elle se referme au retour des mesures.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class NoDataDetectionTests {
  @Autowired private NoDataDetectionService noData;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private jakarta.persistence.EntityManager em;

  private Equipment server;

  @BeforeEach
  void data() {
    String unique = UUID.randomUUID().toString();
    Site site = sites.saveAndFlush(new Site("ND-" + unique.substring(0, 8), "Site sans donnée"));
    server =
        equipments.saveAndFlush(
            new Equipment(
                "ND-EQ-" + unique,
                "Serveur bavard",
                "10.70."
                    + (Math.floorMod(unique.hashCode(), 250) + 1)
                    + "."
                    + (Math.floorMod(unique.substring(9).hashCode(), 250) + 1),
                EquipmentType.SERVER,
                EquipmentStatus.UP,
                site));
  }

  /**
   * Un indicateur mesuré toutes les heures sur la veille, puis plus rien depuis {@code silence}.
   */
  private void regularUntil(String key, Duration silence) {
    Instant now = Instant.now();
    for (int hours = 23; hours >= 0; hours--) {
      Instant at = now.minus(Duration.ofHours(hours));
      if (at.isAfter(now.minus(silence))) {
        continue;
      }
      measure(key, at);
    }
  }

  private void measure(String key, Instant at) {
    jdbc.update(
        "INSERT INTO metric(equipment_id, metric_key, metric_value, unit, quality, collected_at,"
            + " received_at) VALUES (?, ?, ?, '%', 'GOOD', ?, ?)",
        server.getId(), key, BigDecimal.valueOf(42), Timestamp.from(at), Timestamp.from(at));
  }

  private List<String> activeMessages() {
    em.flush();
    return jdbc.queryForList(
        "SELECT message FROM alert WHERE equipment_id = ? AND type = 'NO_DATA'"
            + " AND state = 'OPEN'",
        String.class,
        server.getId());
  }

  @Test
  void unIndicateurRegulierQuiSeTaitOuvreUneAlerteExpliquee() {
    regularUntil("disk.usage[/data]", Duration.ofHours(3));

    noData.scan();
    noData.scan();

    assertThat(activeMessages())
        .hasSize(1)
        .first(org.assertj.core.api.InstanceOfAssertFactories.STRING)
        .startsWith("Plus de mesure pour disk.usage[/data] depuis ")
        .contains("alors que l'équipement répond par ailleurs");
  }

  @Test
  void unIndicateurQuiArriveEncoreNOuvreRien() {
    Instant now = Instant.now();
    for (int hours = 23; hours >= 0; hours--) {
      measure("cpu.usage", now.minus(Duration.ofHours(hours)));
    }
    measure("cpu.usage", now.minusSeconds(60));

    noData.scan();

    assertThat(activeMessages()).isEmpty();
  }

  @Test
  void unIndicateurSansHabitudeNOuvreRien() {
    // Trois relevés seulement sur la veille : un silence ne veut rien dire.
    Instant now = Instant.now();
    for (int hours : new int[] {20, 18, 16}) {
      measure("memory.usage", now.minus(Duration.ofHours(hours)));
    }

    noData.scan();

    assertThat(activeMessages()).isEmpty();
  }

  @Test
  void unEquipementDejaMuetPourToutNOuvrePasDAlerteParIndicateur() {
    regularUntil("cpu.usage", Duration.ofHours(3));
    jdbc.update(
        "INSERT INTO alert(id, equipment_id, occurrence_key, type, state, severity, message,"
            + " triggered_at) VALUES (?, ?, ?, 'COLLECTION', 'OPEN', 'CRITICAL', 'Muet', now())",
        UUID.randomUUID(),
        server.getId(),
        "collection:" + server.getId());

    noData.scan();

    assertThat(activeMessages()).isEmpty();
  }

  @Test
  void leRetourDesMesuresResoutLAlerte() {
    regularUntil("net.in", Duration.ofHours(3));
    noData.scan();
    assertThat(activeMessages()).hasSize(1);

    measure("net.in", Instant.now().minusSeconds(30));
    noData.scan();

    assertThat(activeMessages()).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "SELECT resolution_type FROM alert WHERE equipment_id = ? AND type = 'NO_DATA'",
                String.class,
                server.getId()))
        .isEqualTo("AUTOMATIC");
  }
}

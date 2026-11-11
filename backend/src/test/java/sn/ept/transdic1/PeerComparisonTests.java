package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Timestamp;
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
import sn.ept.transdic1.alerting.service.PeerComparisonService;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.EquipmentStatus;
import sn.ept.transdic1.inventory.domain.EquipmentType;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.SiteRepository;

/**
 * Écart aux semblables sur une vraie base : un équipement qui se démarque de ceux du même type et
 * du même site ouvre une alerte, qui se résout quand il rentre dans le rang.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PeerComparisonTests {
  @Autowired private PeerComparisonService peers;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private jakarta.persistence.EntityManager em;

  private Site site;
  private List<Equipment> switches;

  @BeforeEach
  void data() {
    String unique = UUID.randomUUID().toString().substring(0, 8);
    site = sites.saveAndFlush(new Site("PEER-" + unique, "Site des semblables"));
    switches =
        List.of(
            equipment("PEER-A-" + unique, EquipmentType.SWITCH),
            equipment("PEER-B-" + unique, EquipmentType.SWITCH),
            equipment("PEER-C-" + unique, EquipmentType.SWITCH),
            equipment("PEER-D-" + unique, EquipmentType.SWITCH),
            equipment("PEER-E-" + unique, EquipmentType.SWITCH));
  }

  private Equipment equipment(String reference, EquipmentType type) {
    return equipments.saveAndFlush(
        new Equipment(
            reference,
            "Commutateur " + reference,
            "10.60."
                + (Math.floorMod(reference.hashCode(), 250) + 1)
                + "."
                + (Math.floorMod(reference.substring(5).hashCode(), 250) + 1),
            type,
            EquipmentStatus.UP,
            site));
  }

  private void measure(Equipment target, double value) {
    jdbc.update(
        "INSERT INTO metric(equipment_id, metric_key, metric_value, unit, quality, collected_at,"
            + " received_at) VALUES (?, 'cpu.usage', ?, '%', 'GOOD', ?, ?)",
        target.getId(),
        BigDecimal.valueOf(value),
        Timestamp.from(Instant.now().minusSeconds(60)),
        Timestamp.from(Instant.now()));
  }

  private void clearMeasures() {
    switches.forEach(s -> jdbc.update("DELETE FROM metric WHERE equipment_id = ?", s.getId()));
  }

  private List<String> activeAlerts(Equipment target) {
    em.flush();
    return jdbc.queryForList(
        "SELECT message FROM alert WHERE equipment_id = ? AND type = 'PEER' AND state = 'OPEN'",
        String.class,
        target.getId());
  }

  @Test
  void celuiQuiSeDemarqueOuvreUneAlerteExpliquee() {
    measure(switches.get(0), 10);
    measure(switches.get(1), 11);
    measure(switches.get(2), 9);
    measure(switches.get(3), 12);
    measure(switches.get(4), 85);

    peers.scan();
    peers.scan();

    assertThat(activeAlerts(switches.get(4)))
        .hasSize(1)
        .first(org.assertj.core.api.InstanceOfAssertFactories.STRING)
        .startsWith("Se démarque de ses semblables : cpu.usage = 85.0 %")
        .contains("4 autres équipements du même type sur ce site sont autour de");
    assertThat(activeAlerts(switches.get(0))).isEmpty();
    assertThat(activeAlerts(switches.get(3))).isEmpty();
  }

  @Test
  void rentrerDansLeRangResoutLAlerte() {
    measure(switches.get(0), 10);
    measure(switches.get(1), 11);
    measure(switches.get(2), 9);
    measure(switches.get(3), 12);
    measure(switches.get(4), 85);
    peers.scan();
    assertThat(activeAlerts(switches.get(4))).hasSize(1);

    clearMeasures();
    switches.forEach(s -> measure(s, 10));
    peers.scan();

    assertThat(activeAlerts(switches.get(4))).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "SELECT resolution_type FROM alert WHERE equipment_id = ? AND type = 'PEER'",
                String.class,
                switches.get(4).getId()))
        .isEqualTo("AUTOMATIC");
  }

  @Test
  void unGroupeTropPetitNeJugePersonne() {
    // Trois commutateurs seulement : « se démarquer » n'a pas de sens.
    measure(switches.get(0), 10);
    measure(switches.get(1), 11);
    measure(switches.get(2), 90);

    peers.scan();

    assertThat(activeAlerts(switches.get(2))).isEmpty();
  }

  @Test
  void onNeComparePasDesEquipementsDeTypesDifferents() {
    String unique = UUID.randomUUID().toString().substring(0, 8);
    // Quatre serveurs peu chargés et un commutateur chargé : ce ne sont pas des semblables.
    List<Equipment> servers =
        List.of(
            equipment("SRV-A-" + unique, EquipmentType.SERVER),
            equipment("SRV-B-" + unique, EquipmentType.SERVER),
            equipment("SRV-C-" + unique, EquipmentType.SERVER),
            equipment("SRV-D-" + unique, EquipmentType.SERVER));
    servers.forEach(s -> measure(s, 10));
    measure(switches.get(0), 90);

    peers.scan();

    assertThat(activeAlerts(switches.get(0))).isEmpty();
  }
}

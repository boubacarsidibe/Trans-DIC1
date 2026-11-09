package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.events.receiver.SnmpTrapReceiver;
import sn.ept.transdic1.events.receiver.SyslogReceiver;
import sn.ept.transdic1.events.service.NetworkEventRetentionService;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.EquipmentStatus;
import sn.ept.transdic1.inventory.domain.EquipmentType;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.SiteRepository;

/** Migration V31 (tables, contraintes, type EVENT) et purge des événements expirés. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class NetworkEventStorageTests {
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private NetworkEventRetentionService retention;
  @Autowired private org.springframework.context.ApplicationContext context;

  private Equipment equipment;

  @BeforeEach
  void setUp() {
    Site site =
        sites.save(new Site("NE-" + UUID.randomUUID().toString().substring(0, 8), "Site de test"));
    equipment =
        equipments.saveAndFlush(
            new Equipment(
                "NE-EQ-" + UUID.randomUUID(),
                "Routeur",
                "10.244."
                    + ThreadLocalRandom.current().nextInt(1, 254)
                    + "."
                    + ThreadLocalRandom.current().nextInt(1, 254),
                EquipmentType.ROUTER,
                EquipmentStatus.UP,
                site));
  }

  private int insertEvent(String source, String severity, Instant at) {
    return jdbc.update(
        "INSERT INTO network_event(id, equipment_id, source, severity, source_address, title,"
            + " details, received_at) VALUES (?, ?, ?, ?, '10.0.0.1', 'titre', 'détails', ?)",
        UUID.randomUUID(),
        equipment.getId(),
        source,
        severity,
        Timestamp.from(at));
  }

  private UUID ruleId(String name) {
    return jdbc.queryForObject("SELECT id FROM event_rule WHERE name = ?", UUID.class, name);
  }

  private int insertAlert(String type, UUID ruleId, UUID eventRuleId) {
    return jdbc.update(
        "INSERT INTO alert(id, equipment_id, rule_id, event_rule_id, occurrence_key, type, state,"
            + " severity, message, triggered_at) VALUES (?, ?, ?, ?, ?, ?, 'OPEN', 'WARNING', 'm', ?)",
        UUID.randomUUID(),
        equipment.getId(),
        ruleId,
        eventRuleId,
        "event:" + UUID.randomUUID(),
        type,
        Timestamp.from(Instant.now()));
  }

  @Test
  void uneSourceHorsListeEstRefusée() {
    assertThat(insertEvent("SYSLOG", "ERROR", Instant.now())).isEqualTo(1);
    assertThatThrownBy(() -> insertEvent("NETFLOW", "ERROR", Instant.now()))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void uneSévéritéHorsListeEstRefusée() {
    assertThatThrownBy(() -> insertEvent("SYSLOG", "GRAVE", Instant.now()))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void lesRécepteursSontDésactivésParDéfaut() {
    assertThat(context.getBeansOfType(SnmpTrapReceiver.class)).isEmpty();
    assertThat(context.getBeansOfType(SyslogReceiver.class)).isEmpty();
  }

  @Test
  void lesRèglesParDéfautSontInactives() {
    Integer total =
        jdbc.queryForObject(
            "SELECT count(*) FROM event_rule WHERE name IN ('Interface coupée (linkDown)',"
                + " 'Échec d''authentification SNMP', 'Erreur système (syslog)') AND NOT active",
            Integer.class);
    assertThat(total).isEqualTo(3);
  }

  @Test
  void uneAlerteEventExigeUneRègleDÉvénementEtUneSeuleActive() {
    UUID linkDown = ruleId("Interface coupée (linkDown)");

    assertThat(insertAlert("EVENT", null, linkDown)).isEqualTo(1);
    assertThatThrownBy(() -> insertAlert("EVENT", null, linkDown))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void uneAlerteEventSansRègleDÉvénementEstRefusée() {
    assertThatThrownBy(() -> insertAlert("EVENT", null, null))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void uneAlerteDeCollecteNePeutPasPorterDeRègleDÉvénement() {
    UUID linkDown = ruleId("Interface coupée (linkDown)");
    assertThatThrownBy(() -> insertAlert("COLLECTION", null, linkDown))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void laPurgeSupprimeLesÉvénementsPlusAnciensQueLaRétention() {
    insertEvent("SYSLOG", "INFO", Instant.now().minus(Duration.ofDays(31)));
    insertEvent("SYSLOG", "INFO", Instant.now().minus(Duration.ofDays(29)));

    int purged = retention.purgeExpired();

    assertThat(purged).isGreaterThanOrEqualTo(1);
    Integer left =
        jdbc.queryForObject(
            "SELECT count(*) FROM network_event WHERE equipment_id = ?",
            Integer.class,
            equipment.getId());
    assertThat(left).isEqualTo(1);
  }

  @Test
  void laPurgeProcèdeParLotsJusquÀÉpuisement() {
    Timestamp old = Timestamp.from(Instant.now().minus(Duration.ofDays(40)));
    int rows = 5_000 + 10;
    jdbc.batchUpdate(
        "INSERT INTO network_event(id, equipment_id, source, severity, source_address, title,"
            + " received_at) VALUES (?, ?, 'SYSLOG', 'INFO', '10.0.0.1', 'vieux', ?)",
        java.util.stream.IntStream.range(0, rows)
            .mapToObj(i -> new Object[] {UUID.randomUUID(), equipment.getId(), old})
            .toList());

    assertThat(retention.purgeExpired()).isGreaterThanOrEqualTo(rows);
    Integer left =
        jdbc.queryForObject(
            "SELECT count(*) FROM network_event WHERE equipment_id = ?",
            Integer.class,
            equipment.getId());
    assertThat(left).isZero();
  }

  @Test
  void supprimerLÉquipementSupprimeSesÉvénements() {
    insertEvent("SYSLOG", "INFO", Instant.now());
    jdbc.update("DELETE FROM equipment WHERE id = ?", equipment.getId());

    Integer left =
        jdbc.queryForObject(
            "SELECT count(*) FROM network_event WHERE equipment_id = ?",
            Integer.class,
            equipment.getId());
    assertThat(left).isZero();
  }
}

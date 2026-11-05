package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import sn.ept.transdic1.alerting.domain.Severity;
import sn.ept.transdic1.events.domain.EventRule;
import sn.ept.transdic1.events.domain.EventSeverity;
import sn.ept.transdic1.events.domain.EventSource;
import sn.ept.transdic1.events.receiver.EventIntake;
import sn.ept.transdic1.events.receiver.ReceivedEvent;
import sn.ept.transdic1.events.repository.EventRuleRepository;
import sn.ept.transdic1.events.service.ActiveEventRules;
import sn.ept.transdic1.events.service.EventSourceDirectory;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.EquipmentStatus;
import sn.ept.transdic1.inventory.domain.EquipmentType;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.SiteRepository;

/**
 * Plusieurs travailleurs, une seule source : une rafale de linkDown n'ouvre qu'une alerte et tous
 * les événements sont stockés ; linkDown puis linkUp sont appliqués dans l'ordre (alerte résolue à
 * la fin), sur de nombreux tours. Données validées en base (pas de transaction de test), nettoyées
 * ensuite.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = EventReceiverTests.RECEIVER_PROPERTIES)
class EventOrderingTests {
  private static final String LINK_DOWN = "1.3.6.1.6.3.1.1.5.3";
  private static final String LINK_UP = "1.3.6.1.6.3.1.1.5.4";

  @Autowired private EventIntake intake;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private EventRuleRepository rules;
  @Autowired private EventSourceDirectory directory;
  @Autowired private ActiveEventRules activeRules;
  @Autowired private JdbcTemplate jdbc;

  private Site site;
  private Equipment equipment;
  private EventRule rule;
  private String address;

  @BeforeEach
  void data() {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    site = sites.save(new Site("EVO-" + suffix, "Site ordre"));
    address =
        "10.246."
            + ThreadLocalRandom.current().nextInt(1, 254)
            + "."
            + ThreadLocalRandom.current().nextInt(1, 254);
    equipment =
        equipments.saveAndFlush(
            new Equipment(
                "EVO-EQ-" + suffix,
                "Routeur ordre",
                address,
                EquipmentType.ROUTER,
                EquipmentStatus.UP,
                site));
    rule = new EventRule("Lien coupé EVO " + suffix, Severity.CRITICAL);
    rule.setSource(EventSource.SNMP_TRAP);
    rule.setOidPrefix(LINK_DOWN);
    rule.setResolveOid(LINK_UP);
    rule.setDedupWindowSeconds(0);
    rule.setEquipmentIds(Set.of(equipment.getId()));
    rule.setActive(true);
    rules.saveAndFlush(rule);
    directory.invalidate();
    activeRules.evict();
    intake.start();
  }

  @AfterEach
  void clean() {
    String alerts = "SELECT id FROM alert WHERE equipment_id = ?";
    jdbc.update("DELETE FROM notification WHERE alert_id IN (" + alerts + ")", equipment.getId());
    jdbc.update(
        "DELETE FROM alert_assignment WHERE alert_id IN (" + alerts + ")", equipment.getId());
    jdbc.update("DELETE FROM alert WHERE equipment_id = ?", equipment.getId());
    jdbc.update("DELETE FROM event_rule WHERE id = ?", rule.getId());
    jdbc.update("DELETE FROM equipment WHERE id = ?", equipment.getId());
    jdbc.update("DELETE FROM site WHERE id = ?", site.getId());
    directory.invalidate();
    activeRules.evict();
  }

  private ReceivedEvent trap(String oid, Instant at) {
    return new ReceivedEvent(
        EventSource.SNMP_TRAP,
        address,
        oid.equals(LINK_DOWN) ? EventSeverity.ERROR : EventSeverity.NOTICE,
        oid.equals(LINK_DOWN) ? "linkDown" : "linkUp",
        "ifIndex = 3",
        oid,
        null,
        null,
        null,
        at);
  }

  private int stored() {
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM network_event WHERE equipment_id = ?",
            Integer.class,
            equipment.getId());
    return count == null ? 0 : count;
  }

  private int alerts(String states) {
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM alert WHERE equipment_id = ? AND state IN (" + states + ")",
            Integer.class,
            equipment.getId());
    return count == null ? 0 : count;
  }

  private static boolean await(BooleanSupplier condition) throws InterruptedException {
    long end = System.nanoTime() + Duration.ofSeconds(20).toNanos();
    while (System.nanoTime() < end) {
      if (condition.getAsBoolean()) {
        return true;
      }
      Thread.sleep(50);
    }
    return condition.getAsBoolean();
  }

  @Test
  void uneRafaleDeLinkDownNOuvreQuUneAlerteEtStockeTout() throws Exception {
    Instant t0 = Instant.now();
    int burst = 40;
    for (int i = 0; i < burst; i++) {
      assertThat(intake.submit(trap(LINK_DOWN, t0.plusMillis(i)))).isTrue();
    }

    assertThat(await(() -> stored() == burst)).isTrue();
    assertThat(await(() -> intake.queued() == 0)).isTrue();
    assertThat(alerts("'OPEN', 'IN_PROGRESS'")).isEqualTo(1);
    assertThat(alerts("'OPEN', 'IN_PROGRESS', 'RESOLVED'")).isEqualTo(1);
  }

  @Test
  void linkDownPuisLinkUpSontAppliquésDansLOrdre() throws Exception {
    Instant t0 = Instant.now();
    int rounds = 30;
    for (int i = 0; i < rounds; i++) {
      assertThat(intake.submit(trap(LINK_DOWN, t0.plusMillis(2L * i)))).isTrue();
      assertThat(intake.submit(trap(LINK_UP, t0.plusMillis(2L * i + 1)))).isTrue();
    }

    assertThat(await(() -> stored() == rounds * 2)).isTrue();
    Thread.sleep(200); // dernière transaction validée
    assertThat(alerts("'OPEN', 'IN_PROGRESS'")).isZero();
    assertThat(alerts("'RESOLVED'")).isEqualTo(1);
  }
}

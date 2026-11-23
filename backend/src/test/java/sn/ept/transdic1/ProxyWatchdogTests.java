package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.alerting.domain.AlertState;
import sn.ept.transdic1.alerting.domain.AlertType;
import sn.ept.transdic1.alerting.repository.AlertRepository;
import sn.ept.transdic1.collection.dto.MeasureInput;
import sn.ept.transdic1.collection.service.MeasureIngestionService;
import sn.ept.transdic1.collection.service.ProxyWatchdogService;
import sn.ept.transdic1.inventory.domain.AgentStatus;
import sn.ept.transdic1.inventory.domain.CollectionAgent;
import sn.ept.transdic1.inventory.domain.CollectionProxy;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.EquipmentStatus;
import sn.ept.transdic1.inventory.domain.EquipmentType;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.repository.CollectionAgentRepository;
import sn.ept.transdic1.inventory.repository.CollectionProxyRepository;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.SiteRepository;

/**
 * Un proxy silencieux veut dire qu'un site entier est muet (ADR 0047) : une alerte de collecte par
 * équipement relayé, et un seul incident à traiter grâce au regroupement (ADR 0039).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ProxyWatchdogTests {
  @Autowired private ProxyWatchdogService watchdog;
  @Autowired private MeasureIngestionService ingestion;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private CollectionProxyRepository proxies;
  @Autowired private CollectionAgentRepository agents;
  @Autowired private AlertRepository alerts;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private jakarta.persistence.EntityManager em;

  private Site site;
  private Equipment first;
  private Equipment second;
  private CollectionProxy proxy;

  @BeforeEach
  void data() {
    String unique = UUID.randomUUID().toString();
    site = sites.save(new Site("PWD-" + unique.substring(0, 8), "Site relayé"));
    first = equipments.save(equipment("PWD-EQ1-" + unique, 1));
    second = equipments.save(equipment("PWD-EQ2-" + unique, 2));
    proxy =
        proxies.save(
            new CollectionProxy(
                site,
                "proxy-" + unique.substring(0, 8),
                AgentStatus.ACTIVE,
                Instant.now().minus(2, ChronoUnit.HOURS)));
    proxies.flush();
    em.flush();
  }

  private Equipment equipment(String code, int offset) {
    return new Equipment(
        code,
        "Commutateur relayé",
        "10.245." + offset + "." + (Math.floorMod(code.hashCode(), 250) + 1),
        EquipmentType.SWITCH,
        EquipmentStatus.UP,
        site);
  }

  private void lastSpokeAgo(Duration ago) {
    proxy.setLastCommunicationAt(Instant.now().minus(ago));
    proxies.saveAndFlush(proxy);
    em.flush();
  }

  private long openCollectionAlerts() {
    em.flush();
    return jdbc.queryForObject(
        "SELECT count(*) FROM alert WHERE type = 'COLLECTION' AND state = 'OPEN'"
            + " AND equipment_id IN (?, ?)",
        Long.class,
        first.getId(),
        second.getId());
  }

  @Test
  @DisplayName("Ce qui compte le plus : un proxy muet signale tout le site, en un seul incident")
  void whatMattersMostAsilentProxyFlagsTheWholeSiteAsOneIncident() {
    lastSpokeAgo(Duration.ofMinutes(30));

    int opened = watchdog.checkProxies();

    // Une alerte par équipement relayé — une alerte est attachée à un équipement, et inventer un
    // équipement « site » aurait menti sur l'inventaire.
    assertThat(opened).isEqualTo(2);
    assertThat(openCollectionAlerts()).isEqualTo(2);

    // Mais un seul incident à traiter : même site, même message, le regroupement fait le reste.
    em.flush();
    Long incidents =
        jdbc.queryForObject(
            "SELECT count(DISTINCT ia.incident_id) FROM incident_alert ia"
                + " WHERE ia.alert_id IN (SELECT id FROM alert WHERE equipment_id IN (?, ?))",
            Long.class,
            first.getId(),
            second.getId());
    assertThat(incidents).isEqualTo(1L);
  }

  @Test
  @DisplayName("Un deuxième passage n'ouvre rien de plus")
  void asecondPassOpensNothingMore() {
    lastSpokeAgo(Duration.ofMinutes(30));

    watchdog.checkProxies();
    assertThat(watchdog.checkProxies()).isZero();
    assertThat(openCollectionAlerts()).isEqualTo(2);
  }

  @Test
  @DisplayName("Un proxy qui parle encore n'ouvre rien")
  void aproxyStillTalkingOpensNothing() {
    lastSpokeAgo(Duration.ofMinutes(2));

    assertThat(watchdog.checkProxies()).isZero();
    assertThat(openCollectionAlerts()).isZero();
  }

  @Test
  @DisplayName("Un équipement qui a son propre agent est laissé à son chien de garde")
  void anequipmentWithItsOwnAgentIsLeftToItsOwnWatchdog() {
    agents.saveAndFlush(
        new CollectionAgent(
            second, "agent-" + UUID.randomUUID(), AgentStatus.ACTIVE, Instant.now()));
    em.flush();
    lastSpokeAgo(Duration.ofMinutes(30));

    // Deux alertes pour une panne n'en font pas une plus visible.
    assertThat(watchdog.checkProxies()).isEqualTo(1);
    assertThat(openCollectionAlerts()).isEqualTo(1);
  }

  @Test
  @DisplayName("Le tampon qui se vide résout l'alerte, même rejoué")
  void theemptyingBufferResolvesTheAlertEvenWhenReplayed() {
    lastSpokeAgo(Duration.ofMinutes(30));
    watchdog.checkProxies();
    assertThat(openCollectionAlerts()).isEqualTo(2);

    // Une mesure vieille de six heures ne prouve rien du présent, mais le fait de la recevoir
    // prouve que la liaison est revenue.
    ingestion.ingestBuffered(
        first.getId(),
        List.of(
            new MeasureInput(
                "cpu.usage",
                new BigDecimal("12"),
                "%",
                null,
                Instant.now().minus(6, ChronoUnit.HOURS))),
        Instant.now().minus(10, ChronoUnit.MINUTES));

    assertThat(
            alerts
                .findFirstByEquipmentIdAndTypeAndStateOrderByResolvedAtDesc(
                    first.getId(), AlertType.COLLECTION, AlertState.RESOLVED)
                .isPresent())
        .isTrue();
    // L'autre équipement n'a rien envoyé : son alerte reste ouverte.
    assertThat(openCollectionAlerts()).isEqualTo(1);
  }

  @Test
  @DisplayName("Un proxy révoqué n'est plus surveillé")
  void arevokedProxyIsNoLongerWatched() {
    lastSpokeAgo(Duration.ofMinutes(30));
    proxy.setStatus(AgentStatus.REVOKED);
    proxy.setRevokedAt(Instant.now());
    proxies.saveAndFlush(proxy);
    em.flush();

    assertThat(watchdog.checkProxies()).isZero();
  }
}

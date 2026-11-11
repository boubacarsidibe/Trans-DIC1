package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import sn.ept.transdic1.alerting.domain.AlertType;
import sn.ept.transdic1.alerting.repository.AlertRepository;
import sn.ept.transdic1.collection.service.AgentWatchdogService;
import sn.ept.transdic1.inventory.domain.AgentStatus;
import sn.ept.transdic1.inventory.domain.CollectionAgent;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.EquipmentStatus;
import sn.ept.transdic1.inventory.domain.EquipmentType;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.repository.CollectionAgentRepository;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.SiteRepository;

/**
 * Le passage planifié du chien de garde, tel que le lance le planificateur : hors transaction de
 * test. Les autres tests appellent checkAgents() depuis une transaction ouverte et ne voyaient pas
 * l'échec (LazyInitializationException) du vrai passage.
 */
@SpringBootTest
@ActiveProfiles("test")
class AgentWatchdogScheduledTests {
  @Autowired private AgentWatchdogService watchdog;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private CollectionAgentRepository agents;
  @Autowired private AlertRepository alerts;
  @Autowired private JdbcTemplate jdbc;

  private Site site;
  private Equipment equipment;

  @BeforeEach
  void data() {
    String unique = UUID.randomUUID().toString();
    site = sites.save(new Site("WDS-" + unique.substring(0, 8), "Site du passage planifié"));
    equipment =
        equipments.save(
            new Equipment(
                "WDS-EQ-" + unique,
                "Serveur muet",
                "10.238."
                    + (Math.floorMod(unique.hashCode(), 250) + 1)
                    + "."
                    + (Math.floorMod(unique.substring(9).hashCode(), 250) + 1),
                EquipmentType.SERVER,
                EquipmentStatus.UP,
                site));
    CollectionAgent agent =
        new CollectionAgent(
            equipment,
            "agent-" + unique.substring(0, 8),
            AgentStatus.ACTIVE,
            Instant.now().minus(2, ChronoUnit.HOURS));
    agent.setLastCommunicationAt(Instant.now().minus(20, ChronoUnit.MINUTES));
    agents.save(agent);
  }

  /** Données réelles (pas de transaction de test) : on les retire pour les autres tests. */
  @AfterEach
  void cleanUp() {
    jdbc.update("DELETE FROM alert WHERE equipment_id = ?", equipment.getId());
    jdbc.update("DELETE FROM collection_agent WHERE equipment_id = ?", equipment.getId());
    jdbc.update("DELETE FROM equipment WHERE id = ?", equipment.getId());
    jdbc.update("DELETE FROM site WHERE id = ?", site.getId());
  }

  @Test
  void theScheduledPassOpensTheCollectionAlertOfASilentAgent() {
    watchdog.scheduledCheck();

    var opened =
        alerts
            .findByEquipmentIdOrderByTriggeredAtDesc(equipment.getId(), Pageable.unpaged())
            .getContent();
    assertThat(opened).hasSize(1);
    assertThat(opened.get(0).getType()).isEqualTo(AlertType.COLLECTION);
  }
}

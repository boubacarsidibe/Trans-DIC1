package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.alerting.domain.Alert;
import sn.ept.transdic1.alerting.domain.AlertEventType;
import sn.ept.transdic1.alerting.domain.AlertState;
import sn.ept.transdic1.alerting.domain.AlertType;
import sn.ept.transdic1.alerting.domain.Maintenance;
import sn.ept.transdic1.alerting.domain.MaintenanceState;
import sn.ept.transdic1.alerting.domain.ResolutionType;
import sn.ept.transdic1.alerting.domain.Severity;
import sn.ept.transdic1.alerting.repository.AlertEventRepository;
import sn.ept.transdic1.alerting.repository.AlertRepository;
import sn.ept.transdic1.alerting.repository.MaintenanceRepository;
import sn.ept.transdic1.collection.dto.MeasureInput;
import sn.ept.transdic1.collection.service.AgentWatchdogService;
import sn.ept.transdic1.collection.service.MeasureIngestionService;
import sn.ept.transdic1.inventory.domain.AgentStatus;
import sn.ept.transdic1.inventory.domain.CollectionAgent;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.EquipmentStatus;
import sn.ept.transdic1.inventory.domain.EquipmentType;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.repository.CollectionAgentRepository;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.SiteRepository;
import sn.ept.transdic1.security.domain.AppUser;
import sn.ept.transdic1.security.domain.Role;
import sn.ept.transdic1.security.repository.UserRepository;

/** Un agent silencieux ouvre une alerte de collecte, qui se résout seule à la prochaine mesure. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AgentWatchdogTests {
  @Autowired private AgentWatchdogService watchdog;
  @Autowired private MeasureIngestionService ingestion;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private CollectionAgentRepository agents;
  @Autowired private AlertRepository alerts;
  @Autowired private AlertEventRepository events;
  @Autowired private MaintenanceRepository maintenances;
  @Autowired private UserRepository users;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private jakarta.persistence.EntityManager em;

  private Equipment equipment;
  private CollectionAgent agent;

  @BeforeEach
  void data() {
    String unique = UUID.randomUUID().toString();
    Site site = sites.save(new Site("WDG-" + unique.substring(0, 8), "Site du chien de garde"));
    equipment =
        equipments.save(
            new Equipment(
                "WDG-EQ-" + unique,
                "Serveur silencieux",
                "10.239."
                    + (Math.floorMod(unique.hashCode(), 250) + 1)
                    + "."
                    + (Math.floorMod(unique.substring(9).hashCode(), 250) + 1),
                EquipmentType.SERVER,
                EquipmentStatus.UP,
                site));
    agent =
        agents.save(
            new CollectionAgent(
                equipment,
                "agent-" + unique.substring(0, 8),
                AgentStatus.ACTIVE,
                Instant.now().minus(2, ChronoUnit.HOURS)));
    agents.flush();
  }

  private List<Alert> alertsOf() {
    return alerts
        .findByEquipmentIdOrderByTriggeredAtDesc(equipment.getId(), Pageable.unpaged())
        .getContent();
  }

  private void lastSpokeAgo(Duration ago) {
    agent.setLastCommunicationAt(Instant.now().minus(ago));
    agents.saveAndFlush(agent);
  }

  private void sendMeasure() {
    ingestion.ingest(
        equipment.getId(),
        List.of(
            new MeasureInput(
                "cpu.usage",
                new BigDecimal("10"),
                "%",
                null,
                Instant.now().minus(1, ChronoUnit.MINUTES))));
  }

  @Test
  void aSilentAgentOpensOneCriticalCollectionAlertWithoutDuplicates() {
    lastSpokeAgo(Duration.ofMinutes(20));

    watchdog.checkAgents();
    watchdog.checkAgents();

    var all = alertsOf();
    assertThat(all).hasSize(1);
    Alert alert = all.get(0);
    assertThat(alert.getType()).isEqualTo(AlertType.COLLECTION);
    assertThat(alert.getState()).isEqualTo(AlertState.OPEN);
    assertThat(alert.getSeverity()).isEqualTo(Severity.CRITICAL);
    assertThat(alert.getRule()).isNull();
    assertThat(alert.getMessage()).contains("Serveur silencieux").contains("ne communique plus");
  }

  @Test
  void anAgentThatSpokeRecentlyOrJustRegisteredIsLeftAlone() {
    lastSpokeAgo(Duration.ofMinutes(1));
    watchdog.checkAgents();
    assertThat(alertsOf()).isEmpty();

    // Enregistré à l'instant, jamais entendu : il dispose du même délai.
    agent.setStatus(AgentStatus.REVOKED);
    agents.saveAndFlush(agent);
    agents.saveAndFlush(
        new CollectionAgent(
            equipment, "agent-new-" + UUID.randomUUID(), AgentStatus.ACTIVE, Instant.now()));
    watchdog.checkAgents();
    assertThat(alertsOf()).isEmpty();
  }

  @Test
  void revokedAgentsAndUnmonitoredEquipmentsAreIgnored() {
    lastSpokeAgo(Duration.ofMinutes(30));
    agent.setStatus(AgentStatus.REVOKED);
    agents.saveAndFlush(agent);
    watchdog.checkAgents();
    assertThat(alertsOf()).isEmpty();

    agent.setStatus(AgentStatus.ACTIVE);
    agents.saveAndFlush(agent);
    em.flush();
    jdbc.update("UPDATE equipment SET monitoring_enabled = FALSE WHERE id = ?", equipment.getId());
    em.clear();
    watchdog.checkAgents();
    assertThat(alertsOf()).isEmpty();
  }

  @Test
  void aMeasureResolvesTheCollectionAlertAutomatically() {
    lastSpokeAgo(Duration.ofMinutes(20));
    watchdog.checkAgents();
    assertThat(alertsOf()).hasSize(1);

    sendMeasure();

    Alert resolved = alertsOf().get(0);
    assertThat(resolved.getState()).isEqualTo(AlertState.RESOLVED);
    assertThat(resolved.getResolutionType()).isEqualTo(ResolutionType.AUTOMATIC);
    assertThat(events.findByAlertIdOrderByOccurredAtAsc(resolved.getId()))
        .extracting(e -> e.getType())
        .containsExactly(AlertEventType.OPENED, AlertEventType.RESOLVED);
  }

  @Test
  void aNewSilenceBeforeArchivingReopensTheSameAlert() {
    lastSpokeAgo(Duration.ofMinutes(20));
    watchdog.checkAgents();
    sendMeasure();
    Alert first = alertsOf().get(0);
    assertThat(first.getState()).isEqualTo(AlertState.RESOLVED);

    lastSpokeAgo(Duration.ofMinutes(30));
    watchdog.checkAgents();

    var all = alertsOf();
    assertThat(all).hasSize(1);
    assertThat(all.get(0).getId()).isEqualTo(first.getId());
    assertThat(all.get(0).getState()).isEqualTo(AlertState.OPEN);
    assertThat(events.findByAlertIdOrderByOccurredAtAsc(first.getId())).hasSize(3);
  }

  @Test
  void aMeasureWithoutAnyAlertChangesNothing() {
    sendMeasure();
    assertThat(alertsOf()).isEmpty();
  }

  @Test
  void duringAMaintenanceTheCollectionAlertIsRecordedWithoutNotification() {
    AppUser planner =
        users.saveAndFlush(
            new AppUser(
                "wdg-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID() + "@example.test",
                "hash",
                Role.ADMIN));
    var maintenance =
        new Maintenance(
            planner,
            Instant.now().minus(1, ChronoUnit.HOURS),
            Instant.now().plus(1, ChronoUnit.HOURS),
            "Redémarrage planifié",
            MaintenanceState.IN_PROGRESS);
    maintenance.setEquipment(equipment);
    maintenances.saveAndFlush(maintenance);
    lastSpokeAgo(Duration.ofMinutes(20));

    watchdog.checkAgents();

    Alert alert = alertsOf().get(0);
    assertThat(events.findByAlertIdOrderByOccurredAtAsc(alert.getId()).get(0).getComment())
        .contains("Maintenance");
  }
}

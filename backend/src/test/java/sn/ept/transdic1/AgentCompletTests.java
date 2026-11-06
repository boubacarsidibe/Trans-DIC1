package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.alerting.domain.Alert;
import sn.ept.transdic1.alerting.domain.AlertRule;
import sn.ept.transdic1.alerting.domain.AlertState;
import sn.ept.transdic1.alerting.domain.ComparisonOperator;
import sn.ept.transdic1.alerting.domain.Severity;
import sn.ept.transdic1.alerting.repository.AlertRepository;
import sn.ept.transdic1.alerting.repository.AlertRuleRepository;
import sn.ept.transdic1.alerting.service.AlertEvaluationService;
import sn.ept.transdic1.collection.dto.AgentSnapshotDto;
import sn.ept.transdic1.collection.service.AgentSnapshotService;
import sn.ept.transdic1.common.metric.MetricKey;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.EquipmentStatus;
import sn.ept.transdic1.inventory.domain.EquipmentType;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.SiteRepository;
import sn.ept.transdic1.monitoring.domain.MeasurementQuality;
import sn.ept.transdic1.monitoring.domain.Metric;
import sn.ept.transdic1.monitoring.repository.MetricRepository;

/** Agent complet : clés à instance, règles génériques, alertes par disque, instantané. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AgentCompletTests {
  private static final Instant NOW = Instant.now().minusSeconds(5);

  @Autowired private MockMvc mockMvc;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private AlertRuleRepository rules;
  @Autowired private AlertRepository alerts;
  @Autowired private AlertEvaluationService evaluation;
  @Autowired private AgentSnapshotService snapshots;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private MetricRepository metrics;

  private Equipment equipment;

  @BeforeEach
  void equipment() {
    var site = sites.save(new Site("CPL-" + UUID.randomUUID().toString().substring(0, 8), "Site"));
    int a = ThreadLocalRandom.current().nextInt(1, 254);
    int b = ThreadLocalRandom.current().nextInt(1, 254);
    equipment =
        equipments.save(
            new Equipment(
                "CPL-" + UUID.randomUUID(),
                "Serveur",
                "10.77." + a + "." + b,
                EquipmentType.SERVER,
                EquipmentStatus.UP,
                site));
    // Les règles livrées visent tous les équipements : on les neutralise pour isoler chaque test.
    jdbc.update("UPDATE alert_rule SET active = FALSE");
  }

  private AlertRule rule(String metricType, String threshold) {
    var rule =
        new AlertRule(
            metricType + " " + UUID.randomUUID(),
            metricType,
            ComparisonOperator.GREATER_THAN,
            new BigDecimal(threshold),
            Duration.ZERO,
            Severity.WARNING);
    rule.setAllEquipment(true);
    return rules.save(rule);
  }

  private List<Alert> active() {
    return alerts.findAll().stream()
        .filter(a -> a.getEquipment().getId().equals(equipment.getId()))
        .filter(a -> a.getState() == AlertState.OPEN)
        .toList();
  }

  private void measure(String key, String value) {
    evaluation.evaluate(equipment, key, new BigDecimal(value), NOW);
    alerts.flush();
  }

  @Test
  void aKeyIsSplitIntoFamilyAndInstance() {
    assertThat(MetricKey.family("disk.usage[C:]")).isEqualTo("disk.usage");
    assertThat(MetricKey.instance("disk.usage[C:]")).isEqualTo("C:");
    assertThat(MetricKey.instance("cpu.usage")).isNull();
    assertThat(MetricKey.ruleTypes("cpu.usage")).containsExactly("cpu.usage");
    assertThat(MetricKey.ruleTypes("net.up[Wi-Fi]")).containsExactly("net.up[Wi-Fi]", "net.up[*]");
  }

  @Test
  void aGenericRuleOpensOneAlertPerDisk() {
    rule("disk.usage[*]", "90");

    measure("disk.usage[C:]", "97");
    measure("disk.usage[D:]", "95");
    measure("disk.usage[E:]", "20");
    measure("disk.usage[C:]", "98");

    assertThat(active()).extracting(Alert::getMetricInstance).containsExactlyInAnyOrder("C:", "D:");
  }

  @Test
  void eachDiskRecoversOnItsOwn() {
    rule("disk.usage[*]", "90");
    measure("disk.usage[C:]", "97");
    measure("disk.usage[D:]", "95");

    measure("disk.usage[C:]", "50");

    assertThat(active()).extracting(Alert::getMetricInstance).containsExactly("D:");
  }

  @Test
  void aRuleOnOneDiskReplacesTheGenericRuleForThatDisk() {
    rule("disk.usage[*]", "90");
    var exact = rule("disk.usage[D:]", "99");

    measure("disk.usage[D:]", "95");
    assertThat(active()).isEmpty();

    measure("disk.usage[D:]", "99.5");
    assertThat(active()).hasSize(1);
    assertThat(active().get(0).getRule().getId()).isEqualTo(exact.getId());
  }

  @Test
  void aRuleOnAllEquipmentNeedsNoScope() {
    rule("cpu.usage", "90");
    measure("cpu.usage", "95");
    assertThat(active()).hasSize(1);
  }

  @Test
  void largeValuesFitInTheColumns() {
    metrics.saveAndFlush(
        new Metric(
            equipment,
            "disk.free[C:]",
            new BigDecimal("8000000000000.25"),
            "B",
            MeasurementQuality.GOOD,
            NOW));
    assertThat(
            jdbc.queryForObject(
                "SELECT metric_value FROM metric WHERE equipment_id = ?",
                BigDecimal.class,
                equipment.getId()))
        .isEqualByComparingTo("8000000000000.25");
  }

  private static AgentSnapshotDto snapshot() {
    var line = new AgentSnapshotDto.ProcessLine("java.exe", 42, 88.5, 1024);
    return new AgentSnapshotDto(
        "srv-01",
        "Windows",
        "10.0",
        "AMD64",
        8,
        16_000_000_000L,
        false,
        new AgentSnapshotDto.Processes(List.of(line), List.of(line)));
  }

  @Test
  void aCpuAlertKeepsTheTopProcessesOfTheMoment() {
    rule("cpu.usage", "90");
    snapshots.store(equipment.getId(), snapshot());

    measure("cpu.usage", "95");

    assertThat(active().get(0).getContext()).contains("java.exe").contains("88.5");
  }

  @Test
  void aDiskAlertDoesNotCopyProcesses() {
    rule("disk.usage[*]", "90");
    snapshots.store(equipment.getId(), snapshot());

    measure("disk.usage[C:]", "95");

    assertThat(active().get(0).getContext()).isNull();
  }

  @Test
  void aStaleSnapshotIsNotCopied() {
    rule("cpu.usage", "90");
    snapshots.store(equipment.getId(), snapshot());
    jdbc.update(
        "UPDATE agent_snapshot SET received_at = ? WHERE equipment_id = ?",
        java.sql.Timestamp.from(NOW.minus(Duration.ofMinutes(30))),
        equipment.getId());
    alerts.flush();

    evaluation.evaluate(equipment, "cpu.usage", new BigDecimal("95"), NOW);

    assertThat(active()).hasSize(1);
  }

  @Test
  void aSecondSnapshotReplacesTheFirst() {
    snapshots.store(equipment.getId(), snapshot());
    snapshots.store(equipment.getId(), snapshot());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM agent_snapshot WHERE equipment_id = ?",
                Integer.class,
                equipment.getId()))
        .isLessThanOrEqualTo(1);
  }

  @Test
  @WithMockUser(roles = "VIEWER")
  void aUserReadsTheInventory() throws Exception {
    snapshots.store(equipment.getId(), snapshot());
    alerts.flush();

    mockMvc
        .perform(get("/api/v1/equipements/" + equipment.getId() + "/agent/instantane"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.snapshot.hostname").value("srv-01"))
        .andExpect(jsonPath("$.snapshot.processes.byCpu[0].name").value("java.exe"));
  }

  @Test
  @WithMockUser(roles = "VIEWER")
  void aMissingInventoryIsNotFound() throws Exception {
    mockMvc
        .perform(get("/api/v1/equipements/" + equipment.getId() + "/agent/instantane"))
        .andExpect(status().isNotFound());
  }

  @Test
  void aSnapshotWithoutAgentTokenIsRefused() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/agent/snapshot")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"hostname\":\"x\",\"os\":\"Linux\"}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void theDeliveredRulesExistAndCoverAllEquipment() {
    for (String name :
        List.of(
            "Disque presque plein",
            "Disque saturé",
            "Mémoire saturée",
            "Processeur saturé",
            "Carte réseau coupée",
            "Service arrêté")) {
      var found = rules.findAll().stream().filter(r -> r.getName().equals(name)).findFirst();
      assertThat(found).as(name).isPresent();
      assertThat(found.get().isAllEquipment()).isTrue();
    }
  }
}

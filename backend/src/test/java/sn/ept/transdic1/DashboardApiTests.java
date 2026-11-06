package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.alerting.domain.Alert;
import sn.ept.transdic1.alerting.domain.AlertRule;
import sn.ept.transdic1.alerting.domain.AlertState;
import sn.ept.transdic1.alerting.domain.AlertType;
import sn.ept.transdic1.alerting.domain.ComparisonOperator;
import sn.ept.transdic1.alerting.domain.Severity;
import sn.ept.transdic1.alerting.repository.AlertRepository;
import sn.ept.transdic1.alerting.repository.AlertRuleRepository;
import sn.ept.transdic1.alerting.service.AlertWorkflowService;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.EquipmentStatus;
import sn.ept.transdic1.inventory.domain.EquipmentType;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.SiteRepository;
import sn.ept.transdic1.monitoring.domain.MeasurementQuality;
import sn.ept.transdic1.monitoring.domain.Metric;
import sn.ept.transdic1.monitoring.repository.MetricRepository;
import sn.ept.transdic1.overview.dto.DashboardResponse.Host;
import sn.ept.transdic1.overview.service.DashboardService;

/** Écran d'accueil : état de chaque équipement, pire alerte, mesures et problèmes. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@WithMockUser(roles = "ADMIN")
class DashboardApiTests {
  private static final Instant NOW = Instant.now().minusSeconds(5);

  @Autowired private DashboardService dashboard;
  @Autowired private AlertWorkflowService workflow;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private AlertRepository alerts;
  @Autowired private AlertRuleRepository rules;
  @Autowired private MetricRepository metrics;
  @Autowired private JdbcTemplate jdbc;

  private Equipment equipment;

  @BeforeEach
  void data() {
    var site = sites.save(new Site("DSH-" + UUID.randomUUID().toString().substring(0, 8), "Site"));
    equipment =
        equipments.saveAndFlush(
            new Equipment(
                "DSH-" + UUID.randomUUID(),
                "AAA serveur de tableau",
                "10.78."
                    + ThreadLocalRandom.current().nextInt(1, 254)
                    + "."
                    + ThreadLocalRandom.current().nextInt(1, 254),
                EquipmentType.SERVER,
                EquipmentStatus.UP,
                site));
  }

  private Alert alert(Severity severity, AlertState state) {
    var alert =
        new Alert(
            equipment,
            "dsh-" + UUID.randomUUID(),
            AlertType.THRESHOLD,
            state,
            severity,
            "Problème de test",
            NOW);
    alert.setRule(
        rules.save(
            new AlertRule(
                "dsh " + UUID.randomUUID(),
                "cpu.usage",
                ComparisonOperator.GREATER_THAN,
                BigDecimal.TEN,
                Duration.ZERO,
                severity)));
    return alerts.saveAndFlush(alert);
  }

  private void metric(String key, String value) {
    metrics.saveAndFlush(
        new Metric(equipment, key, new BigDecimal(value), "%", MeasurementQuality.GOOD, NOW));
  }

  private Host host() {
    return dashboard.dashboard().hosts().stream()
        .filter(h -> h.id().equals(equipment.getId()))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void anEquipmentWithoutDataAppearsWithoutValues() {
    var host = host();
    assertThat(host.status()).isEqualTo("UP");
    assertThat(host.worstSeverity()).isNull();
    assertThat(host.activeAlerts()).isZero();
    assertThat(host.cpu()).isNull();
    assertThat(host.lastSeen()).isNull();
  }

  @Test
  void theWorstActiveSeverityColoursTheEquipment() {
    alert(Severity.WARNING, AlertState.OPEN);
    alert(Severity.CRITICAL, AlertState.IN_PROGRESS);
    var host = host();
    assertThat(host.worstSeverity()).isEqualTo("CRITICAL");
    assertThat(host.activeAlerts()).isEqualTo(2);
  }

  @Test
  void aResolvedAlertNoLongerCounts() {
    var alert = alert(Severity.CRITICAL, AlertState.OPEN);
    jdbc.update(
        "UPDATE alert SET state = 'ARCHIVED', resolved_at = now(), resolution_type = 'MANUAL'"
            + " WHERE id = ?",
        alert.getId());
    assertThat(host().worstSeverity()).isNull();
  }

  @Test
  void theFullestDiskIsShown() {
    metric("cpu.usage", "12.5");
    metric("memory.usage", "40");
    metric("disk.usage", "50");
    metric("disk.usage[C:]", "50");
    metric("disk.usage[D:]", "93");

    var host = host();
    assertThat(host.cpu()).isEqualByComparingTo("12.5");
    assertThat(host.memory()).isEqualByComparingTo("40");
    assertThat(host.disk()).isEqualByComparingTo("93");
    assertThat(host.lastSeen()).isNotNull();
  }

  @Test
  void problemsAreSortedByGravity() {
    alert(Severity.WARNING, AlertState.OPEN);
    alert(Severity.CRITICAL, AlertState.OPEN);
    var mine =
        dashboard.dashboard().problems().stream()
            .filter(p -> p.equipmentId().equals(equipment.getId()))
            .toList();
    assertThat(mine).hasSize(2);
    assertThat(mine.get(0).severity()).isEqualTo("CRITICAL");
  }

  @Test
  void aCorrelatedAlertIsHiddenUnderItsRoot() {
    var root = alert(Severity.CRITICAL, AlertState.OPEN);
    var child = alert(Severity.WARNING, AlertState.OPEN);
    jdbc.update("UPDATE alert SET parent_alert_id = ? WHERE id = ?", root.getId(), child.getId());
    var mine =
        dashboard.dashboard().problems().stream()
            .filter(p -> p.equipmentId().equals(equipment.getId()))
            .toList();
    assertThat(mine).extracting(p -> p.id()).containsExactly(root.getId());
  }

  @Test
  void activityCoversTwentyFourHoursEvenWithoutAlerts() {
    alert(Severity.INFO, AlertState.OPEN);
    var activity = dashboard.dashboard().activity();
    assertThat(activity).hasSize(24);
    assertThat(activity.stream().mapToLong(h -> h.opened()).sum()).isGreaterThanOrEqualTo(1);
  }

  @Test
  void alertsCanBeFilteredBySeverityAndActivity() {
    alert(Severity.CRITICAL, AlertState.OPEN);
    alert(Severity.WARNING, AlertState.OPEN);
    var page =
        workflow.list(null, equipment.getId(), Severity.CRITICAL, true, PageRequest.of(0, 10));
    assertThat(page.getContent()).hasSize(1);
    assertThat(page.getContent().get(0).severity()).isEqualTo(Severity.CRITICAL);
  }

  private void history(String key, double start, double perHour, int hours) {
    for (int i = hours; i >= 0; i--) {
      metrics.save(
          new Metric(
              equipment,
              key,
              BigDecimal.valueOf(start + (hours - i) * perHour),
              "%",
              MeasurementQuality.GOOD,
              NOW.minus(Duration.ofHours(i))));
    }
    metrics.flush();
  }

  @Test
  void aDiskFillingUpGetsAForecast() {
    history("disk.usage[C:]", 60, 1, 20); // 80 % maintenant, +1 % par heure : plein dans 20 h
    var forecast =
        dashboard.dashboard().forecasts().stream()
            .filter(f -> f.equipmentId().equals(equipment.getId()))
            .toList();
    assertThat(forecast).hasSize(1);
    assertThat(forecast.get(0).disk()).isEqualTo("C:");
    assertThat(forecast.get(0).daysLeft().doubleValue()).isBetween(0.7, 1.0);
  }

  @Test
  void aStableDiskHasNoForecast() {
    history("disk.usage[D:]", 60, 0, 20);
    assertThat(dashboard.dashboard().forecasts())
        .noneMatch(f -> f.equipmentId().equals(equipment.getId()));
  }

  @Test
  void aNoisyDiskHasNoForecast() {
    for (int i = 20; i >= 0; i--) {
      metrics.save(
          new Metric(
              equipment,
              "disk.usage[E:]",
              BigDecimal.valueOf(i % 2 == 0 ? 40 : 80),
              "%",
              MeasurementQuality.GOOD,
              NOW.minus(Duration.ofHours(i))));
    }
    metrics.flush();
    assertThat(dashboard.dashboard().forecasts())
        .noneMatch(f -> f.equipmentId().equals(equipment.getId()));
  }

  @Test
  void aTooRecentTrendHasNoForecast() {
    history("disk.usage[F:]", 60, 1, 3);
    assertThat(dashboard.dashboard().forecasts())
        .noneMatch(f -> f.equipmentId().equals(equipment.getId()));
  }
}

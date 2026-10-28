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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.alerting.domain.Alert;
import sn.ept.transdic1.alerting.domain.AlertEventType;
import sn.ept.transdic1.alerting.domain.AlertRule;
import sn.ept.transdic1.alerting.domain.AlertState;
import sn.ept.transdic1.alerting.domain.ComparisonOperator;
import sn.ept.transdic1.alerting.domain.Maintenance;
import sn.ept.transdic1.alerting.domain.MaintenanceState;
import sn.ept.transdic1.alerting.domain.ResolutionType;
import sn.ept.transdic1.alerting.domain.RuleEquipmentScope;
import sn.ept.transdic1.alerting.domain.RuleSiteScope;
import sn.ept.transdic1.alerting.domain.Severity;
import sn.ept.transdic1.alerting.repository.AlertEventRepository;
import sn.ept.transdic1.alerting.repository.AlertRepository;
import sn.ept.transdic1.alerting.repository.AlertRuleRepository;
import sn.ept.transdic1.alerting.repository.MaintenanceRepository;
import sn.ept.transdic1.alerting.repository.RuleEquipmentScopeRepository;
import sn.ept.transdic1.alerting.repository.RuleSiteScopeRepository;
import sn.ept.transdic1.collection.dto.MeasureInput;
import sn.ept.transdic1.collection.service.MeasureIngestionService;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.EquipmentStatus;
import sn.ept.transdic1.inventory.domain.EquipmentType;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.SiteRepository;
import sn.ept.transdic1.security.domain.AppUser;
import sn.ept.transdic1.security.domain.Role;
import sn.ept.transdic1.security.repository.UserRepository;

/**
 * Moteur d'alertes de bout en bout : les mesures passent par l'ingestion, qui les stocke puis les
 * fait évaluer, sur un vrai PostgreSQL (index unique partiel compris).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AlertEvaluationTests {
  @Autowired private MeasureIngestionService ingestion;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private UserRepository users;
  @Autowired private AlertRuleRepository rules;
  @Autowired private RuleEquipmentScopeRepository equipmentScopes;
  @Autowired private RuleSiteScopeRepository siteScopes;
  @Autowired private AlertRepository alerts;
  @Autowired private AlertEventRepository events;
  @Autowired private MaintenanceRepository maintenances;

  private Site site;
  private Equipment equipment;
  private Instant base;

  @BeforeEach
  void data() {
    String unique = UUID.randomUUID().toString();
    site = sites.save(new Site("ALR-" + unique.substring(0, 8), "Site d'alertes"));
    equipment = newEquipment(site, unique, 246);
    base = Instant.now().truncatedTo(ChronoUnit.MINUTES).minus(2, ChronoUnit.HOURS);
  }

  private Equipment newEquipment(Site owner, String unique, int network) {
    Equipment created =
        equipments.save(
            new Equipment(
                "ALR-EQ-" + UUID.randomUUID(),
                "Serveur d'alertes",
                "10."
                    + network
                    + "."
                    + (Math.floorMod(unique.hashCode(), 250) + 1)
                    + "."
                    + (Math.floorMod(UUID.randomUUID().hashCode(), 250) + 1),
                EquipmentType.SERVER,
                EquipmentStatus.UP,
                owner));
    equipments.flush();
    return created;
  }

  private AlertRule rule(String confirmationSeconds, String recovery) {
    var rule =
        new AlertRule(
            "cpu-" + UUID.randomUUID(),
            "cpu.usage",
            ComparisonOperator.GREATER_THAN,
            new BigDecimal("90"),
            Duration.ofSeconds(Long.parseLong(confirmationSeconds)),
            Severity.CRITICAL);
    rule.setUnit("%");
    if (recovery != null) {
      rule.setRecoveryThreshold(new BigDecimal(recovery));
    }
    rule = rules.save(rule);
    equipmentScopes.save(new RuleEquipmentScope(rule, equipment));
    return rule;
  }

  private void send(Equipment target, long minutes, String value) {
    send(target, "cpu.usage", base.plus(minutes, ChronoUnit.MINUTES), value, null);
  }

  private void send(Equipment target, String key, Instant at, String value, String quality) {
    ingestion.ingest(
        target.getId(), List.of(new MeasureInput(key, new BigDecimal(value), "%", quality, at)));
  }

  private List<Alert> alertsOf(Equipment target) {
    return alerts
        .findByEquipmentIdOrderByTriggeredAtDesc(
            target.getId(), org.springframework.data.domain.Pageable.unpaged())
        .getContent();
  }

  @Test
  void aBreachWithoutConfirmationOpensOneAlertAndDoesNotDuplicateIt() {
    var rule = rule("0", null);

    send(equipment, 0, "50");
    assertThat(alertsOf(equipment)).isEmpty();
    send(equipment, 1, "95");
    send(equipment, 2, "97");
    send(equipment, 3, "99");

    var opened = alertsOf(equipment);
    assertThat(opened).hasSize(1);
    Alert alert = opened.get(0);
    assertThat(alert.getState()).isEqualTo(AlertState.OPEN);
    assertThat(alert.getSeverity()).isEqualTo(Severity.CRITICAL);
    assertThat(alert.getRule().getId()).isEqualTo(rule.getId());
    assertThat(alert.getMessage()).contains("cpu.usage = 95 %").contains("GREATER_THAN 90");
    assertThat(events.findByAlertIdOrderByOccurredAtAsc(alert.getId()))
        .extracting(e -> e.getType())
        .containsExactly(AlertEventType.OPENED);
  }

  @Test
  void aBreachMustLastForTheConfirmationDurationBeforeOpeningAnAlert() {
    rule("300", null);

    for (long minute = 0; minute < 5; minute++) {
      send(equipment, minute, "95");
    }
    assertThat(alertsOf(equipment)).isEmpty();

    send(equipment, 5, "95");
    assertThat(alertsOf(equipment)).hasSize(1);
  }

  @Test
  void aNormalMeasureRestartsTheConfirmationDuration() {
    rule("300", null);

    send(equipment, 0, "95");
    send(equipment, 1, "95");
    send(equipment, 2, "50");
    for (long minute = 3; minute <= 6; minute++) {
      send(equipment, minute, "95");
    }
    assertThat(alertsOf(equipment)).isEmpty();

    send(equipment, 8, "95");
    assertThat(alertsOf(equipment)).hasSize(1);
  }

  @Test
  void hysteresisKeepsTheAlertOpenUntilTheRecoveryThresholdThenResolvesIt() {
    rule("0", "80");
    send(equipment, 0, "95");
    Alert alert = alertsOf(equipment).get(0);

    send(equipment, 1, "85");
    assertThat(alertsOf(equipment).get(0).getState()).isEqualTo(AlertState.OPEN);

    send(equipment, 2, "79");
    Alert resolved = alertsOf(equipment).get(0);
    assertThat(resolved.getId()).isEqualTo(alert.getId());
    assertThat(resolved.getState()).isEqualTo(AlertState.RESOLVED);
    assertThat(resolved.getResolutionType()).isEqualTo(ResolutionType.AUTOMATIC);
    assertThat(resolved.getResolvedAt()).isEqualTo(base.plus(2, ChronoUnit.MINUTES));
    assertThat(events.findByAlertIdOrderByOccurredAtAsc(alert.getId()))
        .extracting(e -> e.getType())
        .containsExactly(AlertEventType.OPENED, AlertEventType.RESOLVED);
  }

  @Test
  void aNewAnomalyBeforeArchivingReopensTheResolvedAlert() {
    rule("0", null);
    send(equipment, 0, "95");
    send(equipment, 1, "50");
    Alert first = alertsOf(equipment).get(0);
    assertThat(first.getState()).isEqualTo(AlertState.RESOLVED);

    send(equipment, 2, "96");

    var all = alertsOf(equipment);
    assertThat(all).hasSize(1);
    assertThat(all.get(0).getId()).isEqualTo(first.getId());
    assertThat(all.get(0).getState()).isEqualTo(AlertState.OPEN);
    assertThat(all.get(0).getResolvedAt()).isNull();
    assertThat(events.findByAlertIdOrderByOccurredAtAsc(first.getId()))
        .extracting(e -> e.getType())
        .containsExactly(AlertEventType.OPENED, AlertEventType.RESOLVED, AlertEventType.OPENED);
  }

  @Test
  void aRuleOnASiteAppliesToItsEquipmentsAndOnlyToThem() {
    var siteRule =
        rules.save(
            new AlertRule(
                "site-" + UUID.randomUUID(),
                "cpu.usage",
                ComparisonOperator.GREATER_THAN,
                new BigDecimal("90"),
                Duration.ZERO,
                Severity.WARNING));
    siteScopes.save(new RuleSiteScope(siteRule, site));
    String unique = UUID.randomUUID().toString();
    Site other = sites.save(new Site("ALO-" + unique.substring(0, 8), "Autre site"));
    Equipment elsewhere = newEquipment(other, unique, 245);
    Equipment sameSite = newEquipment(site, UUID.randomUUID().toString(), 244);

    send(equipment, 0, "95");
    send(sameSite, 0, "95");
    send(elsewhere, 0, "95");

    assertThat(alertsOf(equipment)).hasSize(1);
    assertThat(alertsOf(sameSite)).hasSize(1);
    assertThat(alertsOf(elsewhere)).isEmpty();
  }

  @Test
  void inactiveRulesAndOtherMetricsAreIgnored() {
    var inactive = rule("0", null);
    inactive.setActive(false);
    rules.save(inactive);

    send(equipment, 0, "99");
    send(equipment, "memory.usage", base.plus(1, ChronoUnit.MINUTES), "99", null);

    assertThat(alertsOf(equipment)).isEmpty();
  }

  @Test
  void badQualityMeasuresAreStoredButNeverOpenNorResolveAlerts() {
    rule("0", null);

    send(equipment, "cpu.usage", base, "99", "BAD");
    assertThat(alertsOf(equipment)).isEmpty();

    send(equipment, 1, "95");
    assertThat(alertsOf(equipment)).hasSize(1);
    send(equipment, "cpu.usage", base.plus(2, ChronoUnit.MINUTES), "10", "BAD");
    assertThat(alertsOf(equipment).get(0).getState()).isEqualTo(AlertState.OPEN);
  }

  @Test
  void duringAMaintenanceTheAlertIsRecordedButNotNotified() {
    rule("0", null);
    AppUser planner =
        users.saveAndFlush(
            new AppUser(
                "maint-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID() + "@example.test",
                "hash",
                Role.ADMIN));
    var maintenance =
        new Maintenance(
            planner,
            base.minus(1, ChronoUnit.HOURS),
            base.plus(1, ChronoUnit.HOURS),
            "Mise à jour",
            MaintenanceState.IN_PROGRESS);
    maintenance.setEquipment(equipment);
    maintenances.saveAndFlush(maintenance);

    send(equipment, 0, "95");

    var alert = alertsOf(equipment).get(0);
    assertThat(events.findByAlertIdOrderByOccurredAtAsc(alert.getId()).get(0).getComment())
        .contains("Maintenance");
  }
}

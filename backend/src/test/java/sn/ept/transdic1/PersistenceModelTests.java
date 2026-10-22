package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.access.domain.EquipmentAccess;
import sn.ept.transdic1.access.domain.SiteAccess;
import sn.ept.transdic1.access.repository.EquipmentAccessRepository;
import sn.ept.transdic1.access.repository.SiteAccessRepository;
import sn.ept.transdic1.alerting.domain.*;
import sn.ept.transdic1.alerting.repository.*;
import sn.ept.transdic1.audit.domain.AuditEntry;
import sn.ept.transdic1.audit.repository.AuditEntryRepository;
import sn.ept.transdic1.inventory.domain.*;
import sn.ept.transdic1.inventory.repository.*;
import sn.ept.transdic1.monitoring.domain.MeasurementQuality;
import sn.ept.transdic1.monitoring.domain.Metric;
import sn.ept.transdic1.monitoring.repository.MetricRepository;
import sn.ept.transdic1.reporting.domain.*;
import sn.ept.transdic1.reporting.repository.*;
import sn.ept.transdic1.security.domain.*;
import sn.ept.transdic1.security.repository.*;

/**
 * Vérifie le modèle persistant : le contexte démarre avec ddl-auto=validate (entités et schéma
 * Flyway concordent), les contraintes du diagramme sont portées par la base, et les requêtes métier
 * des repositories fonctionnent. Les index uniques partiels sont propres à PostgreSQL et vérifiés
 * hors de ce test.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PersistenceModelTests {
  @Autowired private EntityManager em;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private UserRepository users;
  @Autowired private UserSessionRepository sessions;
  @Autowired private PasswordResetTokenRepository resetTokens;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private MonitoredServiceRepository services;
  @Autowired private CollectionConfigurationRepository configurations;
  @Autowired private MetricDefinitionRepository definitions;
  @Autowired private CollectionAgentRepository agents;
  @Autowired private MetricRepository metrics;
  @Autowired private SiteAccessRepository siteAccesses;
  @Autowired private EquipmentAccessRepository equipmentAccesses;
  @Autowired private AlertRuleRepository rules;
  @Autowired private RuleSiteScopeRepository ruleSites;
  @Autowired private RuleEquipmentScopeRepository ruleEquipments;
  @Autowired private AlertRepository alerts;
  @Autowired private AlertAssignmentRepository assignments;
  @Autowired private AlertEventRepository events;
  @Autowired private MaintenanceRepository maintenances;
  @Autowired private NotificationPreferenceRepository preferences;
  @Autowired private NotificationRepository notifications;
  @Autowired private NotificationAttemptRepository attempts;
  @Autowired private ReportRepository reports;
  @Autowired private ReportScheduleRepository schedules;
  @Autowired private ScheduleRecipientRepository recipients;
  @Autowired private ScheduleSiteScopeRepository scheduleSites;
  @Autowired private ScheduleEquipmentScopeRepository scheduleEquipments;
  @Autowired private AuditEntryRepository audits;

  private static final Instant NOW = Instant.parse("2026-10-22T10:00:00Z");
  private AppUser user;
  private Site site;
  private Equipment equipment;

  @BeforeEach
  void baseData() {
    user =
        users.save(new AppUser("model-user", "model-user@example.test", "hash", Role.NOC_OPERATOR));
    site = sites.save(new Site("MODEL-SITE", "Site de test"));
    // Base H2 partagée entre les classes de test : adresse et code uniques à chaque exécution.
    int a = ThreadLocalRandom.current().nextInt(1, 254);
    int b = ThreadLocalRandom.current().nextInt(1, 254);
    equipment =
        equipments.save(
            new Equipment(
                "MODEL-EQ-" + UUID.randomUUID(),
                "Serveur de test",
                "10.250." + a + "." + b,
                EquipmentType.SERVER,
                EquipmentStatus.UP,
                site));
  }

  private void flushAndClear() {
    em.flush();
    em.clear();
  }

  private AlertRule rule() {
    return rules.save(
        new AlertRule(
            "CPU haut",
            "cpu.usage",
            ComparisonOperator.GREATER_THAN,
            new BigDecimal("90"),
            Duration.ofMinutes(5),
            Severity.CRITICAL));
  }

  private Alert thresholdAlert(AlertRule rule, String key, AlertState state) {
    var alert =
        new Alert(equipment, key, AlertType.THRESHOLD, state, Severity.CRITICAL, "CPU > 90 %", NOW);
    alert.setRule(rule);
    return alert;
  }

  // ------------------------------------------------------------------ graphe complet

  @Test
  void theWholeModelIsPersistedAndReadBack() {
    var session =
        sessions.save(
            new UserSession(user, "session-hash", Instant.now().plus(1, ChronoUnit.HOURS)));
    var reset =
        resetTokens.save(
            new PasswordResetToken(user, "reset-hash", Instant.now().plus(30, ChronoUnit.MINUTES)));
    siteAccesses.save(new SiteAccess(user, site, NOW));
    equipmentAccesses.save(new EquipmentAccess(user, equipment, NOW));

    var service = new MonitoredService(equipment, "SSH", ServiceType.TCP_PORT);
    service.setPort(22);
    services.save(service);

    var config =
        new CollectionConfiguration(
            equipment,
            "SNMP principal",
            CollectionMode.SNMP,
            Duration.ofMinutes(5),
            Duration.ofSeconds(10),
            2);
    config.setSnmpVersion(SnmpVersion.V3);
    config.setSecretReference("vault://snmp/switch-core");
    configurations.save(config);
    var definition =
        definitions.save(
            new MetricDefinition(config, "Charge CPU", "cpu.usage", ValueType.DECIMAL));
    var agent = agents.save(new CollectionAgent(equipment, "agent-001", AgentStatus.ACTIVE, NOW));

    var metric =
        new Metric(
            equipment, "cpu.usage", new BigDecimal("93.5"), "%", MeasurementQuality.GOOD, NOW);
    metric.setDefinition(definition);
    metric.setService(service);
    metric.setAgent(agent);
    metrics.save(metric);

    var rule = rule();
    ruleSites.save(new RuleSiteScope(rule, site));
    ruleEquipments.save(new RuleEquipmentScope(rule, equipment));
    var alert = alerts.save(thresholdAlert(rule, "occ-full-graph", AlertState.OPEN));
    assignments.save(new AlertAssignment(alert, user, NOW));
    events.save(new AlertEvent(alert, AlertEventType.OPENED, NOW));

    var maintenance =
        new Maintenance(
            user, NOW, NOW.plus(2, ChronoUnit.HOURS), "Mise à jour", MaintenanceState.PLANNED);
    maintenance.setSite(site);
    maintenances.save(maintenance);

    preferences.save(new NotificationPreference(user, NotificationChannel.EMAIL, Severity.WARNING));
    var notification =
        new Notification(
            user,
            NotificationChannel.EMAIL,
            "noc@example.test",
            "Alerte CPU",
            NotificationState.PENDING);
    notification.setAlert(alert);
    notifications.save(notification);
    attempts.save(new NotificationAttempt(notification, 1, SendResult.FAILURE, NOW));

    var schedule =
        schedules.save(
            new ReportSchedule(
                user,
                "Rapport hebdomadaire",
                ReportType.WEEKLY,
                ReportFrequency.WEEKLY,
                LocalTime.of(7, 0)));
    recipients.save(new ScheduleRecipient(schedule, user));
    scheduleSites.save(new ScheduleSiteScope(schedule, site));
    scheduleEquipments.save(new ScheduleEquipmentScope(schedule, equipment));
    var report =
        new Report(
            user,
            ReportType.WEEKLY,
            NOW.minus(7, ChronoUnit.DAYS),
            NOW,
            ReportState.PENDING,
            ReportFormat.PDF);
    report.setSchedule(schedule);
    reports.save(report);

    audits.save(
        new AuditEntry(
            user,
            user.getUsername(),
            "MODEL_TEST",
            "EQUIPMENT",
            equipment.getId(),
            "corr-1",
            "test"));

    flushAndClear();

    var reloaded = alerts.findById(alert.getId()).orElseThrow();
    assertThat(reloaded.getRule().getId()).isEqualTo(rule.getId());
    assertThat(reloaded.getEquipment().getId()).isEqualTo(equipment.getId());
    assertThat(configurations.findById(config.getId()).orElseThrow().getPollInterval())
        .isEqualTo(Duration.ofMinutes(5));
    assertThat(schedules.findById(schedule.getId()).orElseThrow().getExecutionTime())
        .isEqualTo(LocalTime.of(7, 0));
    assertThat(metrics.findById(metric.getId()).orElseThrow().getDefinition().getTechnicalKey())
        .isEqualTo("cpu.usage");
    assertThat(sessions.findById(session.getId())).isPresent();
    assertThat(resetTokens.findById(reset.getId())).isPresent();
    assertThat(
            audits.findByTargetTypeAndTargetIdOrderByPerformedAtDesc(
                "EQUIPMENT", equipment.getId(), PageRequest.of(0, 10)))
        .hasSize(1);
  }

  @Test
  void everyPersistentEntityOfTheDiagramIsMapped() {
    var mapped =
        em.getMetamodel().getEntities().stream().map(e -> e.getJavaType().getSimpleName()).toList();
    assertThat(mapped)
        .contains(
            "AppUser",
            "UserSession",
            "PasswordResetToken",
            "SiteAccess",
            "EquipmentAccess",
            "Site",
            "Equipment",
            "MonitoredService",
            "CollectionConfiguration",
            "MetricDefinition",
            "CollectionAgent",
            "Metric",
            "AlertRule",
            "RuleSiteScope",
            "RuleEquipmentScope",
            "Alert",
            "AlertAssignment",
            "AlertEvent",
            "Maintenance",
            "NotificationPreference",
            "Notification",
            "NotificationAttempt",
            "Report",
            "ReportSchedule",
            "ScheduleRecipient",
            "ScheduleSiteScope",
            "ScheduleEquipmentScope",
            "AuditEntry")
        .hasSize(28);
  }

  @Test
  void alertStatesAndEquipmentTypesFollowTheDiagram() {
    assertThat(AlertState.values())
        .extracting(Enum::name)
        .containsExactly("OPEN", "IN_PROGRESS", "RESOLVED", "ARCHIVED", "DELETED");
    assertThat(EquipmentType.values())
        .extracting(Enum::name)
        .containsExactly("SERVER", "ROUTER", "SWITCH", "VIRTUAL_MACHINE", "WIFI_ACCESS_POINT");
  }

  // ------------------------------------------------------------------ contraintes

  private void assertRejected(Runnable action) {
    assertThatThrownBy(
            () -> {
              action.run();
              em.flush();
            })
        .hasRootCauseInstanceOf(java.sql.SQLIntegrityConstraintViolationException.class);
  }

  @Test
  void maintenanceTargetsExactlyOneOfSiteOrEquipment() {
    var both = new Maintenance(user, NOW, NOW.plusSeconds(3600), "x", MaintenanceState.PLANNED);
    both.setSite(site);
    both.setEquipment(equipment);
    assertRejected(() -> maintenances.save(both));
  }

  @Test
  void maintenanceWithoutTargetIsRejected() {
    assertRejected(
        () ->
            maintenances.save(
                new Maintenance(user, NOW, NOW.plusSeconds(3600), "x", MaintenanceState.PLANNED)));
  }

  @Test
  void maintenanceMustEndAfterItStarts() {
    var m = new Maintenance(user, NOW, NOW, "x", MaintenanceState.PLANNED);
    m.setSite(site);
    assertRejected(() -> maintenances.save(m));
  }

  @Test
  void thresholdAlertRequiresARule() {
    assertRejected(
        () ->
            alerts.save(
                new Alert(
                    equipment,
                    "occ-no-rule",
                    AlertType.THRESHOLD,
                    AlertState.OPEN,
                    Severity.INFO,
                    "m",
                    NOW)));
  }

  @Test
  void collectionAlertMustNotHaveARule() {
    var alert =
        new Alert(
            equipment,
            "occ-collect-rule",
            AlertType.COLLECTION,
            AlertState.OPEN,
            Severity.INFO,
            "m",
            NOW);
    alert.setRule(rule());
    assertRejected(() -> alerts.save(alert));
  }

  @Test
  void resolvedAlertNeedsADateAndAResolutionType() {
    var alert = thresholdAlert(rule(), "occ-resolved", AlertState.RESOLVED);
    assertRejected(() -> alerts.save(alert));
  }

  @Test
  void occurrenceKeyIsUnique() {
    var rule = rule();
    alerts.saveAndFlush(thresholdAlert(rule, "occ-dup", AlertState.OPEN));
    assertRejected(() -> alerts.save(thresholdAlert(rule, "occ-dup", AlertState.RESOLVED)));
  }

  @Test
  void notificationComesFromExactlyOneAlertOrReport() {
    var none =
        new Notification(
            user, NotificationChannel.EMAIL, "a@example.test", "s", NotificationState.PENDING);
    assertRejected(() -> notifications.save(none));
  }

  @Test
  void notificationCannotComeFromBothAnAlertAndAReport() {
    var alert = alerts.save(thresholdAlert(rule(), "occ-both", AlertState.OPEN));
    var report =
        reports.save(
            new Report(
                user,
                ReportType.DAILY,
                NOW.minusSeconds(86400),
                NOW,
                ReportState.PENDING,
                ReportFormat.PDF));
    var n =
        new Notification(
            user, NotificationChannel.EMAIL, "a@example.test", "s", NotificationState.PENDING);
    n.setAlert(alert);
    n.setReport(report);
    assertRejected(() -> notifications.save(n));
  }

  @Test
  void attemptNumbersStartAtOneAndAreUniquePerNotification() {
    var alert = alerts.save(thresholdAlert(rule(), "occ-attempt", AlertState.OPEN));
    var n =
        new Notification(
            user, NotificationChannel.EMAIL, "a@example.test", "s", NotificationState.PENDING);
    n.setAlert(alert);
    notifications.save(n);
    attempts.saveAndFlush(new NotificationAttempt(n, 1, SendResult.FAILURE, NOW));
    assertRejected(() -> attempts.save(new NotificationAttempt(n, 1, SendResult.SUCCESS, NOW)));
  }

  @Test
  void attemptNumberZeroIsRejected() {
    var alert = alerts.save(thresholdAlert(rule(), "occ-zero", AlertState.OPEN));
    var n =
        new Notification(
            user, NotificationChannel.EMAIL, "a@example.test", "s", NotificationState.PENDING);
    n.setAlert(alert);
    notifications.save(n);
    assertRejected(() -> attempts.save(new NotificationAttempt(n, 0, SendResult.FAILURE, NOW)));
  }

  @Test
  void snmpConfigurationNeedsAVersion() {
    assertRejected(
        () ->
            configurations.save(
                new CollectionConfiguration(
                    equipment,
                    "snmp",
                    CollectionMode.SNMP,
                    Duration.ofMinutes(1),
                    Duration.ofSeconds(5),
                    1)));
  }

  @Test
  void agentConfigurationCannotCarrySnmpParameters() {
    var config =
        new CollectionConfiguration(
            equipment,
            "agent",
            CollectionMode.AGENT,
            Duration.ofMinutes(1),
            Duration.ofSeconds(5),
            1);
    config.setCommunityReference("vault://community");
    assertRejected(() -> configurations.save(config));
  }

  @Test
  void configurationDurationsMustBePositive() {
    assertRejected(
        () ->
            configurations.save(
                new CollectionConfiguration(
                    equipment,
                    "zero",
                    CollectionMode.AGENT,
                    Duration.ZERO,
                    Duration.ofSeconds(5),
                    1)));
  }

  @Test
  void servicePortMustBeValid() {
    var service = new MonitoredService(equipment, "bad-port", ServiceType.TCP_PORT);
    service.setPort(70000);
    assertRejected(() -> services.save(service));
  }

  @Test
  void metricDefinitionKeyIsUniquePerConfiguration() {
    var config =
        new CollectionConfiguration(
            equipment,
            "agent",
            CollectionMode.AGENT,
            Duration.ofMinutes(1),
            Duration.ofSeconds(5),
            1);
    configurations.save(config);
    definitions.saveAndFlush(new MetricDefinition(config, "CPU", "cpu.usage", ValueType.DECIMAL));
    assertRejected(
        () ->
            definitions.save(
                new MetricDefinition(config, "CPU bis", "cpu.usage", ValueType.DECIMAL)));
  }

  @Test
  void accessGrantsAreUniquePerUserAndTarget() {
    siteAccesses.saveAndFlush(new SiteAccess(user, site, NOW));
    assertRejected(() -> siteAccesses.save(new SiteAccess(user, site, NOW)));
  }

  @Test
  void aUserHasAtMostOneNotificationPreference() {
    preferences.saveAndFlush(
        new NotificationPreference(user, NotificationChannel.EMAIL, Severity.INFO));
    assertRejected(
        () ->
            preferences.save(
                new NotificationPreference(user, NotificationChannel.EMAIL, Severity.CRITICAL)));
  }

  @Test
  void reportPeriodMustBeOrdered() {
    assertRejected(
        () ->
            reports.save(
                new Report(
                    user, ReportType.DAILY, NOW, NOW, ReportState.PENDING, ReportFormat.PDF)));
  }

  @Test
  void tokensMustExpireAfterTheirCreation() {
    assertRejected(
        () -> sessions.save(new UserSession(user, "expired-hash", Instant.now().minusSeconds(60))));
  }

  @Test
  void invalidEnumValuesAreRejectedByTheDatabase() {
    em.flush();
    assertThatThrownBy(
            () -> jdbc.update("UPDATE site SET status = 'INVALID' WHERE id = ?", site.getId()))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  // ------------------------------------------------------------------ compositions

  @Test
  void deletingAnEquipmentDeletesItsServicesAndConfigurations() {
    services.save(new MonitoredService(equipment, "HTTP", ServiceType.HTTP));
    configurations.save(
        new CollectionConfiguration(
            equipment,
            "agent",
            CollectionMode.AGENT,
            Duration.ofMinutes(1),
            Duration.ofSeconds(5),
            1));
    flushAndClear();

    equipments.deleteById(equipment.getId());
    flushAndClear();

    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM monitored_service WHERE equipment_id = ?",
                Integer.class,
                equipment.getId()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM collection_configuration WHERE equipment_id = ?",
                Integer.class,
                equipment.getId()))
        .isZero();
  }

  @Test
  void deletingAConfigurationDeletesItsMetricDefinitions() {
    var config =
        configurations.save(
            new CollectionConfiguration(
                equipment,
                "agent",
                CollectionMode.AGENT,
                Duration.ofMinutes(1),
                Duration.ofSeconds(5),
                1));
    definitions.save(new MetricDefinition(config, "CPU", "cpu.usage", ValueType.DECIMAL));
    flushAndClear();

    configurations.deleteById(config.getId());
    flushAndClear();

    assertThat(definitions.findByConfigurationIdAndActiveTrue(config.getId())).isEmpty();
  }

  @Test
  void deletingAnAlertDeletesItsHistory() {
    var alert = alerts.save(thresholdAlert(rule(), "occ-history", AlertState.OPEN));
    events.save(new AlertEvent(alert, AlertEventType.OPENED, NOW));
    flushAndClear();

    alerts.deleteById(alert.getId());
    flushAndClear();

    assertThat(events.findByAlertIdOrderByOccurredAtAsc(alert.getId())).isEmpty();
  }

  // ------------------------------------------------------------------ secrets

  @Test
  void collectionSecretsAreNeverSerializedNorPrinted() throws Exception {
    var config =
        new CollectionConfiguration(
            equipment,
            "snmp",
            CollectionMode.SNMP,
            Duration.ofMinutes(1),
            Duration.ofSeconds(5),
            1);
    config.setSnmpVersion(SnmpVersion.V3);
    config.setCommunityReference("vault://community-secret-ref");
    config.setSecurityIdentity("vault://identity-secret-ref");
    config.setSecretReference("vault://auth-secret-ref");

    String json =
        new ObjectMapper()
            .disable(com.fasterxml.jackson.databind.MapperFeature.REQUIRE_HANDLERS_FOR_JAVA8_TIMES)
            .writeValueAsString(config);

    assertThat(json)
        .doesNotContain("secret-ref")
        .doesNotContain("community")
        .doesNotContain("securityIdentity");
    assertThat(config.toString()).doesNotContain("secret-ref");
  }

  @Test
  void noEntityOverridesToStringSoNoFieldCanLeakIntoLogs() {
    var offenders =
        em.getMetamodel().getEntities().stream()
            .map(e -> e.getJavaType())
            .filter(c -> c.isAnnotationPresent(Entity.class))
            .filter(
                c -> {
                  try {
                    c.getDeclaredMethod("toString");
                    return true;
                  } catch (NoSuchMethodException e) {
                    return false;
                  }
                })
            .toList();
    assertThat(offenders).isEmpty();
  }

  // ------------------------------------------------------------------ requêtes métier

  @Test
  void dueConfigurationsAreFoundByNextRun() {
    var due =
        new CollectionConfiguration(
            equipment,
            "due",
            CollectionMode.AGENT,
            Duration.ofMinutes(1),
            Duration.ofSeconds(5),
            1);
    due.setNextRunAt(NOW.minusSeconds(10));
    var later =
        new CollectionConfiguration(
            equipment,
            "later",
            CollectionMode.AGENT,
            Duration.ofMinutes(1),
            Duration.ofSeconds(5),
            1);
    later.setNextRunAt(NOW.plusSeconds(600));
    configurations.saveAll(List.of(due, later));

    assertThat(configurations.findByActiveTrueAndNextRunAtLessThanEqual(NOW))
        .extracting(CollectionConfiguration::getName)
        .containsExactly("due");
  }

  @Test
  void anActiveAlertIsFoundPerEquipmentAndRuleButNotAResolvedOne() {
    var rule = rule();
    alerts.save(thresholdAlert(rule, "occ-active", AlertState.IN_PROGRESS));

    assertThat(
            alerts.findFirstByEquipmentIdAndRuleIdAndStateIn(
                equipment.getId(), rule.getId(), List.of(AlertState.OPEN, AlertState.IN_PROGRESS)))
        .isPresent();
    assertThat(
            alerts.findFirstByEquipmentIdAndRuleIdAndStateIn(
                equipment.getId(), rule.getId(), List.of(AlertState.RESOLVED)))
        .isEmpty();
    assertThat(alerts.countByStateIn(List.of(AlertState.OPEN, AlertState.IN_PROGRESS)))
        .isEqualTo(1);
    assertThat(alerts.findByState(AlertState.IN_PROGRESS, PageRequest.of(0, 10)).getTotalElements())
        .isEqualTo(1);
  }

  @Test
  void maintenanceCoveringAnEquipmentOrItsSiteIsFoundWhileActive() {
    var bySite =
        new Maintenance(
            user,
            NOW.minusSeconds(60),
            NOW.plusSeconds(3600),
            "site",
            MaintenanceState.IN_PROGRESS);
    bySite.setSite(site);
    var past =
        new Maintenance(
            user,
            NOW.minusSeconds(7200),
            NOW.minusSeconds(3600),
            "passée",
            MaintenanceState.COMPLETED);
    past.setEquipment(equipment);
    maintenances.saveAll(List.of(bySite, past));

    var covering =
        maintenances.findCovering(
            equipment.getId(),
            site.getId(),
            NOW,
            List.of(MaintenanceState.PLANNED, MaintenanceState.IN_PROGRESS));

    assertThat(covering).extracting(Maintenance::getReason).containsExactly("site");
  }

  @Test
  void metricHistoryIsPagedAndOrderedAndLatestIsFound() {
    for (int i = 0; i < 5; i++) {
      metrics.save(
          new Metric(
              equipment,
              "ram.usage",
              new BigDecimal(50 + i),
              "%",
              MeasurementQuality.GOOD,
              NOW.plusSeconds(i * 60L)));
    }
    var page =
        metrics.findByEquipmentIdAndKeyAndCollectedAtBetweenOrderByCollectedAtAsc(
            equipment.getId(), "ram.usage", NOW, NOW.plusSeconds(180), PageRequest.of(0, 3));

    assertThat(page.getTotalElements()).isEqualTo(4);
    assertThat(page.getContent()).hasSize(3);
    assertThat(page.getContent().get(0).getCollectedAt())
        .isBefore(page.getContent().get(2).getCollectedAt());
    assertThat(
            metrics
                .findFirstByEquipmentIdAndKeyOrderByCollectedAtDesc(equipment.getId(), "ram.usage")
                .orElseThrow()
                .getValue())
        .isEqualByComparingTo("54");
  }

  @Test
  void anAssignmentIsActiveUntilItHasAnEndDate() {
    var alert = alerts.save(thresholdAlert(rule(), "occ-assign", AlertState.IN_PROGRESS));
    var assignment = assignments.save(new AlertAssignment(alert, user, NOW));

    assertThat(assignments.findByAlertIdAndEndedAtIsNull(alert.getId())).isPresent();
    assignment.setEndedAt(NOW.plusSeconds(60));
    em.flush();
    assertThat(assignments.findByAlertIdAndEndedAtIsNull(alert.getId())).isEmpty();
  }

  @Test
  void notificationsToSendAreReturnedOldestFirstAndPaged() {
    var alert = alerts.save(thresholdAlert(rule(), "occ-notif", AlertState.OPEN));
    for (int i = 0; i < 3; i++) {
      var n =
          new Notification(
              user,
              NotificationChannel.EMAIL,
              "n" + i + "@example.test",
              "s" + i,
              NotificationState.PENDING);
      n.setAlert(alert);
      notifications.save(n);
    }
    var page =
        notifications.findByStateOrderByCreatedAtAsc(
            NotificationState.PENDING, PageRequest.of(0, 2));
    assertThat(page.getTotalElements()).isEqualTo(3);
    assertThat(page.getContent()).hasSize(2);
  }

  @Test
  void unknownUsersAndCodesAreLookedUpByBusinessKey() {
    assertThat(users.existsByEmail("model-user@example.test")).isTrue();
    assertThat(users.existsByUsername("personne")).isFalse();
    assertThat(sites.findByCode("MODEL-SITE")).isPresent();
    assertThat(equipments.countBySiteId(site.getId())).isEqualTo(1);
    assertThat(equipments.findByMonitoringEnabledTrue())
        .extracting(Equipment::getInventoryCode)
        .contains(equipment.getInventoryCode());
    assertThat(UUID.randomUUID()).isNotEqualTo(equipment.getId());
  }
}

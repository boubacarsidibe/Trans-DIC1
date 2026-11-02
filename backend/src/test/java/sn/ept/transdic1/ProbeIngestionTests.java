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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.alerting.domain.Alert;
import sn.ept.transdic1.alerting.domain.AlertRule;
import sn.ept.transdic1.alerting.domain.AlertState;
import sn.ept.transdic1.alerting.domain.AlertType;
import sn.ept.transdic1.alerting.domain.ComparisonOperator;
import sn.ept.transdic1.alerting.domain.Maintenance;
import sn.ept.transdic1.alerting.domain.MaintenanceState;
import sn.ept.transdic1.alerting.domain.RuleEquipmentScope;
import sn.ept.transdic1.alerting.domain.Severity;
import sn.ept.transdic1.alerting.repository.AlertEventRepository;
import sn.ept.transdic1.alerting.repository.AlertRepository;
import sn.ept.transdic1.alerting.repository.AlertRuleRepository;
import sn.ept.transdic1.alerting.repository.MaintenanceRepository;
import sn.ept.transdic1.alerting.repository.RuleEquipmentScopeRepository;
import sn.ept.transdic1.alerting.service.CollectionAlertService;
import sn.ept.transdic1.collection.probe.ProbeResult;
import sn.ept.transdic1.collection.service.MeasureIngestionService;
import sn.ept.transdic1.inventory.domain.CollectionConfiguration;
import sn.ept.transdic1.inventory.domain.CollectionMode;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.EquipmentStatus;
import sn.ept.transdic1.inventory.domain.EquipmentType;
import sn.ept.transdic1.inventory.domain.MonitoredService;
import sn.ept.transdic1.inventory.domain.ServiceType;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.domain.SnmpVersion;
import sn.ept.transdic1.inventory.repository.CollectionConfigurationRepository;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.MonitoredServiceRepository;
import sn.ept.transdic1.inventory.repository.SiteRepository;
import sn.ept.transdic1.monitoring.domain.Metric;
import sn.ept.transdic1.monitoring.repository.MetricRepository;
import sn.ept.transdic1.security.domain.AppUser;
import sn.ept.transdic1.security.domain.Role;
import sn.ept.transdic1.security.repository.UserRepository;

/**
 * Chemin d'ingestion des sondes : les mesures sont stockées avec leur service et évaluées, sans
 * toucher au statut de l'équipement ni à l'alerte de collecte (sauf ICMP seul pilote), sur un vrai
 * PostgreSQL.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ProbeIngestionTests {
  @Autowired private MeasureIngestionService ingestion;
  @Autowired private CollectionAlertService collectionAlerts;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private MonitoredServiceRepository services;
  @Autowired private CollectionConfigurationRepository configurations;
  @Autowired private AlertRuleRepository rules;
  @Autowired private RuleEquipmentScopeRepository equipmentScopes;
  @Autowired private AlertRepository alerts;
  @Autowired private AlertEventRepository events;
  @Autowired private MaintenanceRepository maintenances;
  @Autowired private MetricRepository metrics;
  @Autowired private UserRepository users;

  private Site site;
  private Instant base;

  @BeforeEach
  void data() {
    String unique = UUID.randomUUID().toString();
    site = sites.save(new Site("PIN-" + unique.substring(0, 8), "Site des sondes"));
    base = Instant.now().truncatedTo(ChronoUnit.MINUTES).minus(2, ChronoUnit.HOURS);
  }

  private Equipment equipment(EquipmentStatus status) {
    Equipment created =
        equipments.save(
            new Equipment(
                "PIN-EQ-" + UUID.randomUUID(),
                "Serveur sondé",
                "10.242."
                    + (Math.floorMod(UUID.randomUUID().hashCode(), 250) + 1)
                    + "."
                    + (Math.floorMod(UUID.randomUUID().hashCode(), 250) + 1),
                EquipmentType.SERVER,
                status,
                site));
    equipments.flush();
    return created;
  }

  private MonitoredService service(Equipment owner, ServiceType type) {
    return services.saveAndFlush(new MonitoredService(owner, "Sonde " + type, type));
  }

  private AlertRule probeUpRule(Equipment owner) {
    var rule =
        rules.save(
            new AlertRule(
                "probe-" + UUID.randomUUID(),
                "probe.up",
                ComparisonOperator.LESS_THAN,
                new BigDecimal("100"),
                Duration.ZERO,
                Severity.CRITICAL));
    equipmentScopes.save(new RuleEquipmentScope(rule, owner));
    return rule;
  }

  private Instant at(long minutes) {
    return base.plus(minutes, ChronoUnit.MINUTES);
  }

  private List<Metric> stored(Equipment owner, MonitoredService service, String key) {
    return metrics.findByEquipmentIdAndServiceIdAndKeyAndCollectedAtBetweenOrderByCollectedAtDesc(
        owner.getId(),
        service.getId(),
        key,
        base.minus(1, ChronoUnit.HOURS),
        Instant.now(),
        Pageable.unpaged());
  }

  private List<Alert> alertsOf(Equipment owner) {
    return alerts
        .findByEquipmentIdOrderByTriggeredAtDesc(owner.getId(), Pageable.unpaged())
        .getContent();
  }

  private EquipmentStatus statusOf(Equipment owner) {
    equipments.flush();
    return equipments.findById(owner.getId()).orElseThrow().getStatus();
  }

  @Test
  void probeFailureStoresUpZeroAndNoLatency() {
    Equipment equipment = equipment(EquipmentStatus.UP);
    MonitoredService web = service(equipment, ServiceType.HTTP);

    var result = ingestion.ingestProbe(web.getId(), ProbeResult.down("Délai dépassé"), at(0));

    assertThat(result.stored()).isEqualTo(1);
    var up = stored(equipment, web, "probe.up");
    assertThat(up).hasSize(1);
    assertThat(up.get(0).getValue()).isEqualByComparingTo("0");
    assertThat(up.get(0).getUnit()).isEqualTo("%");
    assertThat(up.get(0).getService().getId()).isEqualTo(web.getId());
    assertThat(stored(equipment, web, "probe.latency")).isEmpty();
    assertThat(stored(equipment, web, "probe.tls.days_left")).isEmpty();

    var reloaded = services.findById(web.getId()).orElseThrow();
    assertThat(reloaded.getLastUp()).isFalse();
    assertThat(reloaded.getLastLatencyMs()).isNull();
    assertThat(reloaded.getLastError()).isEqualTo("Délai dépassé");
    assertThat(reloaded.getLastCheckedAt()).isEqualTo(at(0));
    assertThat(reloaded.getConsecutiveFailures()).isEqualTo(1);

    ingestion.ingestProbe(web.getId(), ProbeResult.down("Délai dépassé"), at(1));
    assertThat(services.findById(web.getId()).orElseThrow().getConsecutiveFailures()).isEqualTo(2);

    ingestion.ingestProbe(web.getId(), ProbeResult.up(42, 30), at(2));
    var recovered = services.findById(web.getId()).orElseThrow();
    assertThat(recovered.getLastUp()).isTrue();
    assertThat(recovered.getLastLatencyMs()).isEqualTo(42);
    assertThat(recovered.getLastError()).isNull();
    assertThat(recovered.getConsecutiveFailures()).isZero();
    assertThat(stored(equipment, web, "probe.latency").get(0).getUnit()).isEqualTo("ms");
    assertThat(stored(equipment, web, "probe.tls.days_left").get(0).getValue())
        .isEqualByComparingTo("30");
    assertThat(stored(equipment, web, "probe.tls.days_left").get(0).getUnit()).isEqualTo("j");
  }

  @Test
  void probeFailureDoesNotMarkEquipmentReachable() {
    Equipment equipment = equipment(EquipmentStatus.DOWN);
    MonitoredService web = service(equipment, ServiceType.HTTP);

    ingestion.ingestProbe(web.getId(), ProbeResult.down("Connexion refusée"), at(0));

    assertThat(statusOf(equipment)).isEqualTo(EquipmentStatus.DOWN);
  }

  @Test
  void probeSuccessDoesNotResolveCollectionAlert() {
    Equipment equipment = equipment(EquipmentStatus.UP);
    MonitoredService web = service(equipment, ServiceType.HTTP);
    collectionAlerts.reportFailure(equipment, "Équipement injoignable en SNMP", at(0));

    ingestion.ingestProbe(web.getId(), ProbeResult.up(12), at(1));

    var collection =
        alertsOf(equipment).stream().filter(a -> a.getType() == AlertType.COLLECTION).toList();
    assertThat(collection).hasSize(1);
    assertThat(collection.get(0).getState()).isEqualTo(AlertState.OPEN);
    assertThat(statusOf(equipment)).isEqualTo(EquipmentStatus.DOWN);
  }

  @Test
  void failureOpensAlertForRuleProbeUp() {
    Equipment equipment = equipment(EquipmentStatus.UP);
    MonitoredService web = service(equipment, ServiceType.HTTP);
    var rule = probeUpRule(equipment);

    var result = ingestion.ingestProbe(web.getId(), ProbeResult.down("Statut inattendu"), at(0));

    assertThat(result.alertsOpened()).isEqualTo(1);
    var opened = alertsOf(equipment);
    assertThat(opened).hasSize(1);
    assertThat(opened.get(0).getRule().getId()).isEqualTo(rule.getId());
    assertThat(opened.get(0).getService().getId()).isEqualTo(web.getId());
    assertThat(opened.get(0).getState()).isEqualTo(AlertState.OPEN);

    var recovered = ingestion.ingestProbe(web.getId(), ProbeResult.up(20), at(1));
    assertThat(recovered.alertsResolved()).isEqualTo(1);
    assertThat(alertsOf(equipment).get(0).getState()).isEqualTo(AlertState.RESOLVED);
  }

  @Test
  void underMaintenanceOpensAlertWithoutNotification() {
    Equipment equipment = equipment(EquipmentStatus.MAINTENANCE);
    MonitoredService ping = service(equipment, ServiceType.ICMP);
    probeUpRule(equipment);
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

    for (long minute = 0; minute < 3; minute++) {
      ingestion.ingestProbe(ping.getId(), ProbeResult.down("Délai dépassé"), at(minute));
    }

    var alert = alertsOf(equipment).get(0);
    assertThat(events.findByAlertIdOrderByOccurredAtAsc(alert.getId()).get(0).getComment())
        .contains("Maintenance");
    assertThat(statusOf(equipment)).isEqualTo(EquipmentStatus.MAINTENANCE);
  }

  @Test
  void icmpDrivesStatusWhenNoAgentNorSnmp() {
    Equipment equipment = equipment(EquipmentStatus.UP);
    MonitoredService ping = service(equipment, ServiceType.ICMP);

    ingestion.ingestProbe(ping.getId(), ProbeResult.down("Délai dépassé"), at(0));
    ingestion.ingestProbe(ping.getId(), ProbeResult.down("Délai dépassé"), at(1));
    assertThat(statusOf(equipment)).isEqualTo(EquipmentStatus.UP);

    ingestion.ingestProbe(ping.getId(), ProbeResult.down("Délai dépassé"), at(2));
    assertThat(statusOf(equipment)).isEqualTo(EquipmentStatus.DOWN);

    ingestion.ingestProbe(ping.getId(), ProbeResult.up(3), at(3));
    assertThat(statusOf(equipment)).isEqualTo(EquipmentStatus.UP);
  }

  @Test
  void icmpDoesNotDriveStatusWhenSnmpConfigured() {
    Equipment equipment = equipment(EquipmentStatus.UP);
    MonitoredService ping = service(equipment, ServiceType.ICMP);
    var configuration =
        new CollectionConfiguration(
            equipment,
            "SNMP principal",
            CollectionMode.SNMP,
            Duration.ofMinutes(1),
            Duration.ofSeconds(1),
            0);
    configuration.setSnmpVersion(SnmpVersion.V2C);
    configurations.saveAndFlush(configuration);

    for (long minute = 0; minute < 3; minute++) {
      ingestion.ingestProbe(ping.getId(), ProbeResult.down("Délai dépassé"), at(minute));
    }

    assertThat(statusOf(equipment)).isEqualTo(EquipmentStatus.UP);
  }

  @Test
  void migrationCreatesTwoInactiveDefaultRules() {
    var defaults =
        rules.findAll().stream()
            .filter(
                r ->
                    r.getName().equals("Sonde injoignable")
                        || r.getName().equals("Certificat bientôt expiré"))
            .toList();
    assertThat(defaults).hasSize(2);

    var unreachable =
        defaults.stream().filter(r -> r.getName().equals("Sonde injoignable")).findFirst().get();
    assertThat(unreachable.getMetricType()).isEqualTo("probe.up");
    assertThat(unreachable.getOperator()).isEqualTo(ComparisonOperator.LESS_THAN);
    assertThat(unreachable.getThreshold()).isEqualByComparingTo("100");
    assertThat(unreachable.getConfirmationDuration()).isEqualTo(Duration.ofSeconds(180));
    assertThat(unreachable.getInitialSeverity()).isEqualTo(Severity.CRITICAL);
    assertThat(unreachable.isActive()).isFalse();

    var certificate =
        defaults.stream()
            .filter(r -> r.getName().equals("Certificat bientôt expiré"))
            .findFirst()
            .get();
    assertThat(certificate.getMetricType()).isEqualTo("probe.tls.days_left");
    assertThat(certificate.getOperator()).isEqualTo(ComparisonOperator.LESS_THAN);
    assertThat(certificate.getThreshold()).isEqualByComparingTo("14");
    assertThat(certificate.getInitialSeverity()).isEqualTo(Severity.WARNING);
    assertThat(certificate.isActive()).isFalse();
  }
}

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
import sn.ept.transdic1.alerting.domain.ComparisonOperator;
import sn.ept.transdic1.alerting.domain.RuleEquipmentScope;
import sn.ept.transdic1.alerting.domain.Severity;
import sn.ept.transdic1.alerting.repository.AlertRepository;
import sn.ept.transdic1.alerting.repository.AlertRuleRepository;
import sn.ept.transdic1.alerting.repository.RuleEquipmentScopeRepository;
import sn.ept.transdic1.alerting.service.AlertEvaluationService;
import sn.ept.transdic1.collection.dto.MeasureInput;
import sn.ept.transdic1.collection.service.MeasureIngestionService;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.EquipmentStatus;
import sn.ept.transdic1.inventory.domain.EquipmentType;
import sn.ept.transdic1.inventory.domain.MonitoredService;
import sn.ept.transdic1.inventory.domain.ServiceType;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.MonitoredServiceRepository;
import sn.ept.transdic1.inventory.repository.SiteRepository;
import sn.ept.transdic1.monitoring.domain.MeasurementQuality;
import sn.ept.transdic1.monitoring.domain.Metric;
import sn.ept.transdic1.monitoring.repository.MetricRepository;

/**
 * Déduplication des alertes par service : deux sondes sur le même équipement ont chacune leur
 * alerte, leur confirmation et leur résolution, sur un vrai PostgreSQL (index uniques partiels
 * compris).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AlertPerServiceTests {
  @Autowired private AlertEvaluationService evaluation;
  @Autowired private MeasureIngestionService ingestion;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private MonitoredServiceRepository services;
  @Autowired private AlertRuleRepository rules;
  @Autowired private RuleEquipmentScopeRepository equipmentScopes;
  @Autowired private AlertRepository alerts;
  @Autowired private MetricRepository metrics;

  private Equipment equipment;
  private MonitoredService web;
  private MonitoredService dns;
  private Instant base;

  @BeforeEach
  void data() {
    String unique = UUID.randomUUID().toString();
    Site site = sites.save(new Site("APS-" + unique.substring(0, 8), "Site des sondes"));
    equipment =
        equipments.save(
            new Equipment(
                "APS-EQ-" + UUID.randomUUID(),
                "Serveur sondé",
                "10.243."
                    + (Math.floorMod(unique.hashCode(), 250) + 1)
                    + "."
                    + (Math.floorMod(UUID.randomUUID().hashCode(), 250) + 1),
                EquipmentType.SERVER,
                EquipmentStatus.UP,
                site));
    equipments.flush();
    web = services.save(new MonitoredService(equipment, "Portail web", ServiceType.HTTP));
    dns = services.save(new MonitoredService(equipment, "Résolveur DNS", ServiceType.DNS));
    services.flush();
    base = Instant.now().truncatedTo(ChronoUnit.MINUTES).minus(2, ChronoUnit.HOURS);
  }

  private AlertRule rule(String metricKey, ComparisonOperator operator, String threshold, long s) {
    var rule =
        rules.save(
            new AlertRule(
                "svc-" + UUID.randomUUID(),
                metricKey,
                operator,
                new BigDecimal(threshold),
                Duration.ofSeconds(s),
                Severity.CRITICAL));
    equipmentScopes.save(new RuleEquipmentScope(rule, equipment));
    return rule;
  }

  private AlertRule probeRule(long confirmationSeconds) {
    return rule("probe.up", ComparisonOperator.LESS_THAN, "100", confirmationSeconds);
  }

  /** Comme une sonde : la mesure est stockée avec son service, puis évaluée. */
  private void probe(MonitoredService service, long minutes, String value) {
    Instant at = base.plus(minutes, ChronoUnit.MINUTES);
    var metric =
        new Metric(equipment, "probe.up", new BigDecimal(value), "%", MeasurementQuality.GOOD, at);
    metric.setService(service);
    metrics.saveAndFlush(metric);
    evaluation.evaluate(equipment, service, "probe.up", new BigDecimal(value), at);
  }

  private List<Alert> alertsOf() {
    return alerts
        .findByEquipmentIdOrderByTriggeredAtDesc(equipment.getId(), Pageable.unpaged())
        .getContent();
  }

  private Alert alertOf(MonitoredService service) {
    return alertsOf().stream()
        .filter(a -> a.getService() != null && a.getService().getId().equals(service.getId()))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void twoServicesOnSameEquipmentOpenTwoAlerts() {
    probeRule(0);

    probe(web, 0, "0");
    probe(dns, 0, "0");
    probe(web, 1, "0");

    var opened = alertsOf();
    assertThat(opened).hasSize(2);
    assertThat(opened).allMatch(a -> a.getState() == AlertState.OPEN);
    assertThat(opened)
        .extracting(a -> a.getService().getId())
        .containsExactlyInAnyOrder(web.getId(), dns.getId());
    assertThat(opened).extracting(Alert::getOccurrenceKey).doesNotHaveDuplicates();
  }

  @Test
  void resolvingOneServiceLeavesTheOtherOpen() {
    probeRule(0);
    probe(web, 0, "0");
    probe(dns, 0, "0");

    probe(web, 1, "100");

    assertThat(alertOf(web).getState()).isEqualTo(AlertState.RESOLVED);
    assertThat(alertOf(dns).getState()).isEqualTo(AlertState.OPEN);

    // La nouvelle panne du web rouvre l'alerte du web, pas celle du DNS.
    Alert webAlert = alertOf(web);
    probe(web, 2, "0");
    assertThat(alertsOf()).hasSize(2);
    assertThat(alertOf(web).getId()).isEqualTo(webAlert.getId());
    assertThat(alertOf(web).getState()).isEqualTo(AlertState.OPEN);
  }

  @Test
  void confirmationUsesOnlyTheServiceHistory() {
    probeRule(180);

    for (long minute = 0; minute <= 2; minute++) {
      probe(web, minute, "0");
    }
    assertThat(alertsOf()).isEmpty();

    probe(dns, 3, "0");
    assertThat(alertsOf()).isEmpty();

    probe(web, 3, "0");
    var opened = alertsOf();
    assertThat(opened).hasSize(1);
    assertThat(opened.get(0).getService().getId()).isEqualTo(web.getId());
  }

  @Test
  void equipmentLevelAlertsUnchanged() {
    var rule = rule("cpu.usage", ComparisonOperator.GREATER_THAN, "90", 120);
    // Des mesures de service en dépassement ne confirment pas une mesure sans service.
    for (long minute = 0; minute <= 2; minute++) {
      var metric =
          new Metric(
              equipment,
              "cpu.usage",
              new BigDecimal("99"),
              "%",
              MeasurementQuality.GOOD,
              base.plus(minute, ChronoUnit.MINUTES));
      metric.setService(web);
      metrics.saveAndFlush(metric);
    }

    send(2, "95");
    assertThat(alertsOf()).isEmpty();
    send(3, "95");
    assertThat(alertsOf()).isEmpty();
    send(4, "96");
    send(5, "97");

    var opened = alertsOf();
    assertThat(opened).hasSize(1);
    Alert alert = opened.get(0);
    assertThat(alert.getService()).isNull();
    assertThat(alert.getRule().getId()).isEqualTo(rule.getId());
    assertThat(alert.getOccurrenceKey())
        .isEqualTo(
            rule.getId()
                + ":"
                + equipment.getId()
                + ":"
                + base.plus(4, ChronoUnit.MINUTES).toEpochMilli());
    assertThat(alert.getMessage()).doesNotContain("[");

    send(6, "50");
    assertThat(alertsOf().get(0).getState()).isEqualTo(AlertState.RESOLVED);
  }

  private void send(long minutes, String value) {
    ingestion.ingest(
        equipment.getId(),
        List.of(
            new MeasureInput(
                "cpu.usage",
                new BigDecimal(value),
                "%",
                null,
                base.plus(minutes, ChronoUnit.MINUTES))));
  }

  @Test
  void messageNamesTheService() {
    probeRule(0);

    probe(dns, 0, "0");

    Alert alert = alertOf(dns);
    assertThat(alert.getMessage()).endsWith(" [Résolveur DNS]");
    assertThat(alert.getOccurrenceKey()).contains(dns.getId().toString());
  }
}

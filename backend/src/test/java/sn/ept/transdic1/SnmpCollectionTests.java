package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.snmp4j.security.AuthSHA;
import org.snmp4j.security.PrivAES128;
import org.snmp4j.security.UsmUser;
import org.snmp4j.smi.Gauge32;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.OctetString;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.alerting.domain.Alert;
import sn.ept.transdic1.alerting.domain.AlertRule;
import sn.ept.transdic1.alerting.domain.AlertState;
import sn.ept.transdic1.alerting.domain.AlertType;
import sn.ept.transdic1.alerting.domain.ComparisonOperator;
import sn.ept.transdic1.alerting.domain.RuleEquipmentScope;
import sn.ept.transdic1.alerting.domain.Severity;
import sn.ept.transdic1.alerting.repository.AlertRepository;
import sn.ept.transdic1.alerting.repository.AlertRuleRepository;
import sn.ept.transdic1.alerting.repository.RuleEquipmentScopeRepository;
import sn.ept.transdic1.collection.service.SnmpCollectionService;
import sn.ept.transdic1.inventory.domain.CollectionConfiguration;
import sn.ept.transdic1.inventory.domain.CollectionMode;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.EquipmentStatus;
import sn.ept.transdic1.inventory.domain.EquipmentType;
import sn.ept.transdic1.inventory.domain.MetricDefinition;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.domain.SnmpVersion;
import sn.ept.transdic1.inventory.domain.ValueType;
import sn.ept.transdic1.inventory.repository.CollectionConfigurationRepository;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.MetricDefinitionRepository;
import sn.ept.transdic1.inventory.repository.SiteRepository;
import sn.ept.transdic1.monitoring.repository.MetricRepository;
import sn.ept.transdic1.support.SnmpTestAgent;

/**
 * Collecte SNMP de bout en bout contre un faux équipement qui répond réellement sur UDP : lecture
 * v2c et v3, mesures stockées, alertes, panne puis reprise, secrets absents ou erronés.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SnmpCollectionTests {
  private static final String CPU_OID = "1.3.6.1.4.1.99999.1.1";
  private static final String MEMORY_OID = "1.3.6.1.4.1.99999.1.2";
  private static final String COMMUNITY = "tdic-test-community";
  private static final String V3_USER = "tdicuser";
  private static final String V3_AUTH = "auth-pass-12345";
  private static final String V3_PRIV = "priv-pass-12345";

  private static final SnmpTestAgent AGENT = startAgent();

  private static SnmpTestAgent startAgent() {
    try {
      return new SnmpTestAgent(
          COMMUNITY,
          new UsmUser(
              new OctetString(V3_USER),
              AuthSHA.ID,
              new OctetString(V3_AUTH),
              PrivAES128.ID,
              new OctetString(V3_PRIV)));
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @AfterAll
  static void stopAgent() throws IOException {
    AGENT.close();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("collection.snmp.default-port", AGENT::port);
    registry.add("SNMP_TEST_COMMUNITY", () -> COMMUNITY);
    registry.add("SNMP_TEST_WRONG", () -> "mauvaise-communaute");
    registry.add("SNMP_TEST_V3", () -> V3_AUTH + "|" + V3_PRIV);
    registry.add("SNMP_TEST_V3_BAD", () -> "autre-phrase-12345|autre-secret-12345");
  }

  @Autowired private SnmpCollectionService collection;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private CollectionConfigurationRepository configurations;
  @Autowired private MetricDefinitionRepository definitions;
  @Autowired private AlertRuleRepository rules;
  @Autowired private RuleEquipmentScopeRepository scopes;
  @Autowired private AlertRepository alerts;
  @Autowired private MetricRepository metrics;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private jakarta.persistence.EntityManager em;

  private Equipment equipment;

  @BeforeEach
  void data() {
    AGENT.clear();
    AGENT.set(CPU_OID, new Gauge32(95));
    AGENT.set(MEMORY_OID, new Integer32(40));
    String unique = UUID.randomUUID().toString();
    Site site = sites.save(new Site("SNM-" + unique.substring(0, 8), "Site SNMP"));
    equipment =
        equipments.save(
            new Equipment(
                "SNM-EQ-" + unique,
                "Switch SNMP",
                "127.0.0.1",
                EquipmentType.SWITCH,
                EquipmentStatus.UP,
                site));
    equipments.flush();
  }

  private CollectionConfiguration configure(SnmpVersion version, String secretReference) {
    var configuration =
        new CollectionConfiguration(
            equipment,
            "SNMP principal",
            CollectionMode.SNMP,
            Duration.ofMinutes(1),
            Duration.ofSeconds(1),
            0);
    configuration.setSnmpVersion(version);
    if (version == SnmpVersion.V2C) {
      configuration.setCommunityReference(secretReference);
    } else {
      configuration.setSecurityIdentity(V3_USER);
      configuration.setSecretReference(secretReference);
    }
    configuration.setNextRunAt(Instant.now().minusSeconds(1));
    configurations.saveAndFlush(configuration);
    metric(configuration, "cpu.usage", CPU_OID, "%");
    metric(configuration, "memory.usage", MEMORY_OID, "%");
    return configuration;
  }

  private void metric(CollectionConfiguration configuration, String key, String oid, String unit) {
    var definition = new MetricDefinition(configuration, key, key, ValueType.INTEGER);
    definition.setSnmpOid(oid);
    definition.setUnit(unit);
    definitions.saveAndFlush(definition);
  }

  private void cpuRule() {
    var rule =
        new AlertRule(
            "snmp-" + UUID.randomUUID(),
            "cpu.usage",
            ComparisonOperator.GREATER_THAN,
            new BigDecimal("90"),
            Duration.ZERO,
            Severity.CRITICAL);
    rule = rules.save(rule);
    scopes.save(new RuleEquipmentScope(rule, equipment));
  }

  private java.util.List<Alert> alertsOf(AlertType type) {
    return alerts
        .findByEquipmentIdOrderByTriggeredAtDesc(equipment.getId(), Pageable.unpaged())
        .getContent()
        .stream()
        .filter(a -> a.getType() == type)
        .toList();
  }

  @Test
  void aV2cReadStoresTheMeasuresAndEvaluatesTheAlertRules() {
    configure(SnmpVersion.V2C, "env:SNMP_TEST_COMMUNITY");
    cpuRule();

    assertThat(collection.collectDue()).isEqualTo(1);

    assertThat(
            metrics
                .findFirstByEquipmentIdAndKeyOrderByCollectedAtDesc(equipment.getId(), "cpu.usage")
                .orElseThrow()
                .getValue())
        .isEqualByComparingTo("95");
    assertThat(
            metrics
                .findFirstByEquipmentIdAndKeyOrderByCollectedAtDesc(
                    equipment.getId(), "memory.usage")
                .orElseThrow()
                .getValue())
        .isEqualByComparingTo("40");
    assertThat(alertsOf(AlertType.THRESHOLD)).hasSize(1);
    assertThat(alertsOf(AlertType.COLLECTION)).isEmpty();
  }

  @Test
  void theNextReadIsScheduledAndNotRepeatedBeforeItsTime() {
    var configuration = configure(SnmpVersion.V2C, "env:SNMP_TEST_COMMUNITY");

    assertThat(collection.collectDue()).isEqualTo(1);
    assertThat(collection.collectDue()).isZero();

    var updated = configurations.findById(configuration.getId()).orElseThrow();
    assertThat(updated.getLastRunAt()).isNotNull();
    assertThat(Duration.between(updated.getLastRunAt(), updated.getNextRunAt()))
        .isEqualTo(Duration.ofMinutes(1));
  }

  @Test
  void aV3AuthPrivReadWorks() {
    configure(SnmpVersion.V3, "env:SNMP_TEST_V3");

    collection.collectDue();

    assertThat(
            metrics
                .findFirstByEquipmentIdAndKeyOrderByCollectedAtDesc(equipment.getId(), "cpu.usage")
                .orElseThrow()
                .getValue())
        .isEqualByComparingTo("95");
    assertThat(alertsOf(AlertType.COLLECTION)).isEmpty();
  }

  @Test
  void aWrongV3SecretOpensACollectionAlertInsteadOfStoringAnything() {
    configure(SnmpVersion.V3, "env:SNMP_TEST_V3_BAD");

    collection.collectDue();

    assertThat(
            metrics.findFirstByEquipmentIdAndKeyOrderByCollectedAtDesc(
                equipment.getId(), "cpu.usage"))
        .isEmpty();
    assertThat(alertsOf(AlertType.COLLECTION)).hasSize(1);
  }

  @Test
  void aSilentEquipmentOpensOneCollectionAlertThatRecoversWithTheNextMeasure() {
    var configuration = configure(SnmpVersion.V2C, "env:SNMP_TEST_WRONG");

    collection.collectDue();

    var failure = alertsOf(AlertType.COLLECTION);
    assertThat(failure).hasSize(1);
    assertThat(failure.get(0).getState()).isEqualTo(AlertState.OPEN);
    assertThat(failure.get(0).getSeverity()).isEqualTo(Severity.CRITICAL);
    assertThat(failure.get(0).getMessage())
        .contains("Switch SNMP")
        .contains("injoignable")
        .doesNotContain("mauvaise-communaute")
        .doesNotContain(COMMUNITY);
    // La lecture suivante est reprogrammée même après un échec.
    assertThat(configurations.findById(configuration.getId()).orElseThrow().getNextRunAt())
        .isAfter(Instant.now());

    // L'équipement revient : la bonne communauté est configurée, la lecture suivante est due.
    configuration.setCommunityReference("env:SNMP_TEST_COMMUNITY");
    configuration.setNextRunAt(Instant.now().minusSeconds(1));
    configurations.saveAndFlush(configuration);
    collection.collectDue();

    var recovered = alertsOf(AlertType.COLLECTION);
    assertThat(recovered).hasSize(1);
    assertThat(recovered.get(0).getState()).isEqualTo(AlertState.RESOLVED);
  }

  @Test
  void aMissingSecretIsAFailureThatNeverRevealsAnyValue() {
    configure(SnmpVersion.V2C, "env:SNMP_TEST_ABSENT");

    collection.collectDue();

    var failure = alertsOf(AlertType.COLLECTION);
    assertThat(failure).hasSize(1);
    assertThat(failure.get(0).getMessage()).doesNotContain(COMMUNITY);
  }

  @Test
  void nonNumericOrMissingValuesAreSkippedButTheOthersAreKept() {
    AGENT.set(CPU_OID, new OctetString("pas-un-nombre"));
    configure(SnmpVersion.V2C, "env:SNMP_TEST_COMMUNITY");

    collection.collectDue();

    assertThat(
            metrics.findFirstByEquipmentIdAndKeyOrderByCollectedAtDesc(
                equipment.getId(), "cpu.usage"))
        .isEmpty();
    assertThat(
            metrics
                .findFirstByEquipmentIdAndKeyOrderByCollectedAtDesc(
                    equipment.getId(), "memory.usage")
                .orElseThrow()
                .getValue())
        .isEqualByComparingTo("40");
    assertThat(alertsOf(AlertType.COLLECTION)).isEmpty();
  }

  @Test
  void whenNothingUsableIsReturnedTheCollectionFails() {
    AGENT.clear();
    configure(SnmpVersion.V2C, "env:SNMP_TEST_COMMUNITY");

    collection.collectDue();

    assertThat(alertsOf(AlertType.COLLECTION)).hasSize(1);
  }

  @Test
  void inactiveConfigurationsAndUnmonitoredEquipmentsAreNotRead() {
    var configuration = configure(SnmpVersion.V2C, "env:SNMP_TEST_COMMUNITY");
    configuration.setActive(false);
    configurations.saveAndFlush(configuration);
    assertThat(collection.collectDue()).isZero();

    configuration.setActive(true);
    configurations.saveAndFlush(configuration);
    em.flush();
    jdbc.update("UPDATE equipment SET monitoring_enabled = FALSE WHERE id = ?", equipment.getId());
    jdbc.update("UPDATE collection_configuration SET next_run_at = now() - interval '1 second'");
    em.clear();

    collection.collectDue();

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM metric WHERE equipment_id = ?",
                Integer.class,
                equipment.getId()))
        .isZero();
    assertThat(alertsOf(AlertType.COLLECTION)).isEmpty();
  }
}

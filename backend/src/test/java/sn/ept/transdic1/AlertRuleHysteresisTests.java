package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.alerting.domain.AlertRule;
import sn.ept.transdic1.alerting.domain.ComparisonOperator;
import sn.ept.transdic1.alerting.domain.Severity;

/** Seuil de retour à la normale (hystérésis) : logique du domaine et contrainte en base. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AlertRuleHysteresisTests {
  @Autowired private JdbcTemplate jdbc;

  private static BigDecimal n(String value) {
    return new BigDecimal(value);
  }

  private static AlertRule rule(ComparisonOperator operator, String threshold, String recovery) {
    var rule =
        new AlertRule(
            "r-" + UUID.randomUUID(),
            "cpu.usage",
            operator,
            n(threshold),
            Duration.ZERO,
            Severity.WARNING);
    if (recovery != null) {
      rule.setRecoveryThreshold(n(recovery));
    }
    return rule;
  }

  @Test
  void withoutRecoveryThresholdTheRuleRecoversAsSoonAsItIsNoLongerBreached() {
    var rule = rule(ComparisonOperator.GREATER_THAN, "90", null);

    assertThat(rule.isBreachedBy(n("90.1"))).isTrue();
    assertThat(rule.isBreachedBy(n("90"))).isFalse();
    assertThat(rule.isRecoveredBy(n("90"))).isTrue();
    assertThat(rule.isRecoveredBy(n("91"))).isFalse();
  }

  @Test
  void betweenTheTwoThresholdsNeitherBreachedNorRecovered() {
    var rule = rule(ComparisonOperator.GREATER_THAN, "90", "80");

    assertThat(rule.isBreachedBy(n("85"))).isFalse();
    assertThat(rule.isRecoveredBy(n("85"))).isFalse();
    assertThat(rule.isRecoveredBy(n("80"))).isTrue();
    assertThat(rule.isRecoveredBy(n("79.9"))).isTrue();
    assertThat(rule.isBreachedBy(n("95"))).isTrue();
  }

  @Test
  void lessThanRulesRecoverAboveTheirRecoveryThreshold() {
    var rule = rule(ComparisonOperator.LESS_THAN, "10", "20");

    assertThat(rule.isBreachedBy(n("9"))).isTrue();
    assertThat(rule.isRecoveredBy(n("15"))).isFalse();
    assertThat(rule.isRecoveredBy(n("20"))).isTrue();
  }

  @Test
  void equalRulesRecoverWhenTheValueChanges() {
    var rule = rule(ComparisonOperator.EQUAL, "0", null);

    assertThat(rule.isBreachedBy(n("0"))).isTrue();
    assertThat(rule.isRecoveredBy(n("1"))).isTrue();
    assertThat(rule.isRecoveredBy(n("0.0"))).isFalse();
  }

  private void insertRule(String operator, String threshold, String recovery) {
    jdbc.update(
        "INSERT INTO alert_rule(id, name, metric_type, operator, threshold, recovery_threshold,"
            + " confirmation_duration_seconds, initial_severity)"
            + " VALUES (?, ?, 'cpu.usage', ?, ?, ?, 0, 'WARNING')",
        UUID.randomUUID(),
        "db-" + UUID.randomUUID(),
        operator,
        n(threshold),
        recovery == null ? null : n(recovery));
  }

  @Test
  void databaseAcceptsConsistentRecoveryThresholds() {
    insertRule("GREATER_THAN", "90", "80");
    insertRule("GREATER_OR_EQUAL", "90", "90");
    insertRule("LESS_THAN", "10", "20");
    insertRule("EQUAL", "0", null);
  }

  @Test
  void databaseRejectsARecoveryThresholdOnTheWrongSideOfTheThreshold() {
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> insertRule("GREATER_THAN", "90", "95"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_alert_rule_recovery_threshold");
  }

  @Test
  void databaseRejectsARecoveryThresholdOnAnEqualRule() {
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> insertRule("EQUAL", "0", "0"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_alert_rule_recovery_threshold");
  }
}

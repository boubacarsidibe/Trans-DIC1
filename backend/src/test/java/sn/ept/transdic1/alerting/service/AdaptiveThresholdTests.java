package sn.ept.transdic1.alerting.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import sn.ept.transdic1.alerting.domain.AlertRule;
import sn.ept.transdic1.alerting.domain.ComparisonOperator;
import sn.ept.transdic1.alerting.domain.Severity;

/** Calcul du seuil adaptatif (ADR 0049), sans base de données. */
class AdaptiveThresholdTests {

  private static AlertRule rule(ComparisonOperator operator, String threshold) {
    var rule =
        new AlertRule(
            "trafic",
            "if.in.rate",
            operator,
            new BigDecimal(threshold),
            Duration.ZERO,
            Severity.WARNING);
    rule.setAdaptive(true);
    return rule;
  }

  private static List<Double> around(double center, double spread, int count, long seed) {
    var random = new Random(seed);
    var values = new ArrayList<Double>();
    for (int i = 0; i < count; i++) {
      values.add(center + random.nextGaussian() * spread);
    }
    return values;
  }

  private static BigDecimal n(double value) {
    return BigDecimal.valueOf(value);
  }

  @Test
  void tooLittleHistoryKeepsTheStartingThresholdOfTheRule() {
    var rule = rule(ComparisonOperator.GREATER_THAN, "80");
    rule.setRecoveryThreshold(new BigDecimal("70"));

    var limits = AdaptiveThreshold.learn(rule, List.of(500.0, 510.0, 495.0), "en semaine vers 9 h");

    assertThat(limits.learned()).isFalse();
    assertThat(limits.threshold()).isEqualByComparingTo("80");
    assertThat(limits.recovery()).isEqualByComparingTo("70");
    assertThat(limits.samples()).isEqualTo(3);
  }

  @Test
  void aBusyEquipmentLearnsAHigherThresholdThanTheStartingOne() {
    // Un lien habituellement chargé à 500 avec des pointes de ± 20 : 80 sonnerait sans arrêt.
    var rule = rule(ComparisonOperator.GREATER_THAN, "80");

    var limits = AdaptiveThreshold.learn(rule, around(500, 20, 20, 1), "en semaine vers 9 h");

    assertThat(limits.learned()).isTrue();
    assertThat(limits.threshold().doubleValue()).isBetween(530.0, 600.0);
    assertThat(limits.breachedBy(rule.getOperator(), n(520))).isFalse();
    assertThat(limits.breachedBy(rule.getOperator(), n(700))).isTrue();
    // Retour à 70 % de la marge : entre la normale et le seuil.
    assertThat(limits.recovery().doubleValue())
        .isGreaterThan(limits.normal().doubleValue())
        .isLessThan(limits.threshold().doubleValue());
  }

  @Test
  void aQuietEquipmentLearnsALowerThresholdAndSeesWhatAFixedOneWouldMiss() {
    // Un poste presque muet à 10 : une montée à 60 est anormale pour lui, 80 ne la verrait pas.
    var rule = rule(ComparisonOperator.GREATER_THAN, "80");

    var limits = AdaptiveThreshold.learn(rule, around(10, 2, 20, 2), "en semaine vers 9 h");

    assertThat(limits.threshold().doubleValue()).isLessThan(30.0);
    assertThat(limits.breachedBy(rule.getOperator(), n(60))).isTrue();
  }

  @Test
  void aHigherSensitivityGivesMoreRoom() {
    var history = around(100, 10, 20, 3);
    var prudent = rule(ComparisonOperator.GREATER_THAN, "80");
    prudent.setAdaptiveSensitivity(new BigDecimal("5"));
    var sensitive = rule(ComparisonOperator.GREATER_THAN, "80");
    sensitive.setAdaptiveSensitivity(new BigDecimal("2"));

    assertThat(AdaptiveThreshold.learn(prudent, history, "s").threshold())
        .isGreaterThan(AdaptiveThreshold.learn(sensitive, history, "s").threshold());
  }

  @Test
  void theAbsoluteLimitWinsOverAPermissiveLearnedThreshold() {
    // Un disque resté plein tout le mois apprendrait « 98 % est normal » : la limite tient bon.
    var rule = rule(ComparisonOperator.GREATER_THAN, "90");
    rule.setAdaptiveLimit(new BigDecimal("95"));

    var limits = AdaptiveThreshold.learn(rule, around(97, 0.5, 20, 4), "s");

    assertThat(limits.learned()).isTrue();
    assertThat(limits.capped()).isTrue();
    assertThat(limits.threshold()).isEqualByComparingTo("95");
    assertThat(limits.recovery().compareTo(limits.threshold())).isLessThanOrEqualTo(0);
    assertThat(limits.breachedBy(rule.getOperator(), n(96))).isTrue();
  }

  @Test
  void theLimitIsIgnoredWhenTheLearnedThresholdIsAlreadyStricter() {
    var rule = rule(ComparisonOperator.GREATER_THAN, "90");
    rule.setAdaptiveLimit(new BigDecimal("95"));

    var limits = AdaptiveThreshold.learn(rule, around(40, 3, 20, 5), "s");

    assertThat(limits.capped()).isFalse();
    assertThat(limits.threshold().doubleValue()).isLessThan(95.0);
  }

  @Test
  void lessThanRulesLearnBelowTheUsualTroughs() {
    // Débit qui ne descend jamais sous 200 aux heures de cours : tomber à 20 est une panne.
    var rule = rule(ComparisonOperator.LESS_THAN, "5");

    var limits = AdaptiveThreshold.learn(rule, around(200, 15, 20, 6), "s");

    assertThat(limits.threshold().doubleValue()).isBetween(100.0, 180.0);
    assertThat(limits.breachedBy(rule.getOperator(), n(20))).isTrue();
    assertThat(limits.breachedBy(rule.getOperator(), n(195))).isFalse();
    assertThat(limits.recovery().doubleValue())
        .isGreaterThan(limits.threshold().doubleValue())
        .isLessThan(limits.normal().doubleValue());
  }

  @Test
  void aPastIncidentInTheHistoryDoesNotRaiseTheThreshold() {
    var clean = around(100, 5, 20, 7);
    var polluted = new ArrayList<>(clean);
    polluted.set(3, 900.0);
    polluted.set(11, 950.0);
    var rule = rule(ComparisonOperator.GREATER_THAN, "80");

    double cleanThreshold = AdaptiveThreshold.learn(rule, clean, "s").threshold().doubleValue();
    double pollutedThreshold =
        AdaptiveThreshold.learn(rule, polluted, "s").threshold().doubleValue();

    assertThat(pollutedThreshold).isLessThan(cleanThreshold * 1.2);
  }

  @Test
  void aPerfectlyFlatSeriesStillLeavesAMargin() {
    var rule = rule(ComparisonOperator.GREATER_THAN, "80");

    var limits = AdaptiveThreshold.learn(rule, java.util.Collections.nCopies(10, 50.0), "s");

    assertThat(limits.breachedBy(rule.getOperator(), n(50.4))).isFalse();
    assertThat(limits.threshold().doubleValue()).isGreaterThan(50.0);
  }

  @Test
  void aFixedRuleKeepsItsWrittenThresholds() {
    var rule = rule(ComparisonOperator.GREATER_THAN, "80");
    rule.setAdaptive(false);

    var limits = AdaptiveThreshold.fixed(rule, 0, null);

    assertThat(limits.threshold()).isEqualByComparingTo("80");
    assertThat(limits.recoveredBy(rule.getOperator(), n(80))).isTrue();
    assertThat(limits.recoveredBy(rule.getOperator(), n(81))).isFalse();
  }
}

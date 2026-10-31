package sn.ept.transdic1.alerting.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import sn.ept.transdic1.alerting.domain.AlertRule;
import sn.ept.transdic1.alerting.domain.ComparisonOperator;
import sn.ept.transdic1.alerting.domain.Severity;
import sn.ept.transdic1.alerting.dto.RuleTestDtos.SimulatedAlert;

/** Rejeu d'une règle, sans base : seuil bas, confirmation nulle, historique vide. */
class RuleReplayTests {
  private static final Instant T0 = Instant.parse("2026-10-31T10:00:00Z");

  private static List<MetricHistory.Sample> samples(int... values) {
    return java.util.stream.IntStream.range(0, values.length)
        .mapToObj(
            i -> new MetricHistory.Sample(T0.plusSeconds(60L * i), BigDecimal.valueOf(values[i])))
        .toList();
  }

  @Test
  void unSeuilBasRetientLaValeurLaPlusBasseCommePic() {
    AlertRule rule =
        new AlertRule(
            "Disque libre",
            "disk.free",
            ComparisonOperator.LESS_THAN,
            BigDecimal.TEN,
            Duration.ZERO,
            Severity.WARNING);
    rule.setRecoveryThreshold(BigDecimal.valueOf(20));

    List<SimulatedAlert> alerts = RuleSimulationService.replay(rule, samples(30, 8, 3, 15, 25, 9));

    assertThat(alerts).hasSize(2);
    assertThat(alerts.get(0))
        .isEqualTo(
            new SimulatedAlert(T0.plusSeconds(60), T0.plusSeconds(240), BigDecimal.valueOf(3)));
    assertThat(alerts.get(1).resolvedAt()).isNull();
    assertThat(alerts.get(1).peakValue()).isEqualByComparingTo("9");
  }

  @Test
  void unHistoriqueVideNOuvreRien() {
    AlertRule rule =
        new AlertRule(
            "CPU",
            "cpu.usage",
            ComparisonOperator.GREATER_THAN,
            BigDecimal.valueOf(90),
            Duration.ofMinutes(5),
            Severity.CRITICAL);
    assertThat(RuleSimulationService.replay(rule, List.of())).isEmpty();
  }
}

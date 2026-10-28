package sn.ept.transdic1.alerting.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import sn.ept.transdic1.alerting.domain.AlertRule;
import sn.ept.transdic1.alerting.domain.ComparisonOperator;
import sn.ept.transdic1.alerting.domain.Severity;

/** Une règle d'alerte et sa portée. */
public record AlertRuleResponse(
    UUID id,
    String name,
    String metricType,
    ComparisonOperator operator,
    BigDecimal threshold,
    BigDecimal recoveryThreshold,
    String unit,
    long confirmationSeconds,
    Severity initialSeverity,
    boolean active,
    List<UUID> siteIds,
    List<UUID> equipmentIds,
    Instant createdAt,
    Instant updatedAt) {

  public static AlertRuleResponse from(
      AlertRule rule, List<UUID> siteIds, List<UUID> equipmentIds) {
    return new AlertRuleResponse(
        rule.getId(),
        rule.getName(),
        rule.getMetricType(),
        rule.getOperator(),
        rule.getThreshold(),
        rule.getRecoveryThreshold(),
        rule.getUnit(),
        rule.getConfirmationDuration().toSeconds(),
        rule.getInitialSeverity(),
        rule.isActive(),
        siteIds,
        equipmentIds,
        rule.getCreatedAt(),
        rule.getUpdatedAt());
  }
}

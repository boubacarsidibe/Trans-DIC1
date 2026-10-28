package sn.ept.transdic1.alerting.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import sn.ept.transdic1.common.persistence.DurationSecondsConverter;

@Entity
@Table(name = "alert_rule")
public class AlertRule {
  @Id private UUID id;

  @Column(name = "name", nullable = false, unique = true, length = 120)
  private String name;

  @Column(name = "metric_type", nullable = false, length = 64)
  private String metricType;

  @Enumerated(EnumType.STRING)
  @Column(name = "operator", nullable = false)
  private ComparisonOperator operator;

  @Column(name = "threshold", nullable = false, precision = 12, scale = 4)
  private BigDecimal threshold;

  /**
   * Seuil de retour à la normale, distinct du seuil d'ouverture (hystérésis) : entre les deux,
   * l'état de l'alerte ne change pas, ce qui évite les alertes qui s'ouvrent et se ferment en
   * boucle. Nul : l'alerte se résout dès que la valeur repasse sous le seuil d'ouverture.
   */
  @Column(name = "recovery_threshold", precision = 12, scale = 4)
  private BigDecimal recoveryThreshold;

  @Column(name = "unit", length = 24)
  private String unit;

  @Convert(converter = DurationSecondsConverter.class)
  @Column(name = "confirmation_duration_seconds", nullable = false)
  private Duration confirmationDuration;

  @Enumerated(EnumType.STRING)
  @Column(name = "initial_severity", nullable = false)
  private Severity initialSeverity;

  @Column(name = "active", nullable = false)
  private boolean active;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected AlertRule() {}

  public AlertRule(
      String name,
      String metricType,
      ComparisonOperator operator,
      BigDecimal threshold,
      Duration confirmationDuration,
      Severity initialSeverity) {
    id = UUID.randomUUID();
    this.name = name;
    this.metricType = metricType;
    this.operator = operator;
    this.threshold = threshold;
    this.confirmationDuration = confirmationDuration;
    this.initialSeverity = initialSeverity;
    active = true;
    createdAt = Instant.now();
    updatedAt = Instant.now();
  }

  @PreUpdate
  void touch() {
    updatedAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getMetricType() {
    return metricType;
  }

  public void setMetricType(String metricType) {
    this.metricType = metricType;
  }

  public ComparisonOperator getOperator() {
    return operator;
  }

  public void setOperator(ComparisonOperator operator) {
    this.operator = operator;
  }

  public BigDecimal getThreshold() {
    return threshold;
  }

  public void setThreshold(BigDecimal threshold) {
    this.threshold = threshold;
  }

  public BigDecimal getRecoveryThreshold() {
    return recoveryThreshold;
  }

  public void setRecoveryThreshold(BigDecimal recoveryThreshold) {
    this.recoveryThreshold = recoveryThreshold;
  }

  /** La valeur franchit le seuil d'ouverture. */
  public boolean isBreachedBy(BigDecimal value) {
    return operator.test(value, threshold);
  }

  /** La valeur est revenue assez loin du seuil pour résoudre l'alerte. */
  public boolean isRecoveredBy(BigDecimal value) {
    if (recoveryThreshold == null) {
      return !isBreachedBy(value);
    }
    return switch (operator) {
      case GREATER_THAN, GREATER_OR_EQUAL -> value.compareTo(recoveryThreshold) <= 0;
      case LESS_THAN, LESS_OR_EQUAL -> value.compareTo(recoveryThreshold) >= 0;
      case EQUAL -> value.compareTo(threshold) != 0;
    };
  }

  public String getUnit() {
    return unit;
  }

  public void setUnit(String unit) {
    this.unit = unit;
  }

  public Duration getConfirmationDuration() {
    return confirmationDuration;
  }

  public void setConfirmationDuration(Duration confirmationDuration) {
    this.confirmationDuration = confirmationDuration;
  }

  public Severity getInitialSeverity() {
    return initialSeverity;
  }

  public void setInitialSeverity(Severity initialSeverity) {
    this.initialSeverity = initialSeverity;
  }

  public boolean isActive() {
    return active;
  }

  public void setActive(boolean active) {
    this.active = active;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}

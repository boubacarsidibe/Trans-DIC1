package sn.ept.transdic1.oncall.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Étape franchie de la chaîne d'escalade d'astreinte pour une alerte : une seule par niveau et par
 * alerte (contrainte unique), ce qui rend la chaîne idempotente même si le passage tourne deux
 * fois.
 */
@Entity
@Table(name = "oncall_alert_step")
public class OnCallAlertStep {
  @Id private UUID id;

  @Column(name = "alert_id", nullable = false)
  private UUID alertId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private OnCallLevel level;

  @Column(name = "user_id")
  private UUID userId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private OnCallOutcome outcome;

  @Column(name = "occurred_at", nullable = false)
  private Instant occurredAt;

  protected OnCallAlertStep() {}

  public OnCallAlertStep(
      UUID alertId, OnCallLevel level, UUID userId, OnCallOutcome outcome, Instant occurredAt) {
    id = UUID.randomUUID();
    this.alertId = alertId;
    this.level = level;
    this.userId = userId;
    this.outcome = outcome;
    this.occurredAt = occurredAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getAlertId() {
    return alertId;
  }

  public OnCallLevel getLevel() {
    return level;
  }

  public UUID getUserId() {
    return userId;
  }

  public OnCallOutcome getOutcome() {
    return outcome;
  }

  public Instant getOccurredAt() {
    return occurredAt;
  }
}

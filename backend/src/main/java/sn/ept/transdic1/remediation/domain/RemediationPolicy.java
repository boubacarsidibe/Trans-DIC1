package sn.ept.transdic1.remediation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Action corrective attachée à une règle d'alerte : une seule par règle. */
@Entity
@Table(name = "remediation_policy")
public class RemediationPolicy {
  @Id private UUID id;

  @Column(name = "rule_id", nullable = false, unique = true)
  private UUID ruleId;

  @Column(name = "action_name", nullable = false, length = 64)
  private String actionName;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private RemediationMode mode;

  @Column(name = "cooldown_minutes", nullable = false)
  private int cooldownMinutes;

  @Column(nullable = false)
  private boolean enabled;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "updated_by", length = 120)
  private String updatedBy;

  protected RemediationPolicy() {}

  public RemediationPolicy(UUID ruleId) {
    this.id = UUID.randomUUID();
    this.ruleId = ruleId;
  }

  public void update(
      String actionName,
      RemediationMode mode,
      int cooldownMinutes,
      boolean enabled,
      String by,
      Instant at) {
    this.actionName = actionName;
    this.mode = mode;
    this.cooldownMinutes = cooldownMinutes;
    this.enabled = enabled;
    this.updatedBy = by;
    this.updatedAt = at;
  }

  public UUID getId() {
    return id;
  }

  public UUID getRuleId() {
    return ruleId;
  }

  public String getActionName() {
    return actionName;
  }

  public RemediationMode getMode() {
    return mode;
  }

  public int getCooldownMinutes() {
    return cooldownMinutes;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public String getUpdatedBy() {
    return updatedBy;
  }
}

package sn.ept.transdic1.remediation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Une exécution d'action corrective sur un équipement. Les transitions sont gardées ici : une
 * exécution terminée ne change plus, et seules les étapes prévues sont possibles.
 */
@Entity
@Table(name = "remediation_job")
public class RemediationJob {
  @Id private UUID id;

  @Column(name = "alert_id")
  private UUID alertId;

  @Column(name = "equipment_id", nullable = false)
  private UUID equipmentId;

  @Column(name = "agent_id")
  private UUID agentId;

  @Column(name = "rule_id")
  private UUID ruleId;

  @Column(name = "action_name", nullable = false, length = 64)
  private String actionName;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private RemediationState state;

  @Enumerated(EnumType.STRING)
  @Column(name = "trigger_kind", nullable = false, length = 10)
  private RemediationTrigger trigger;

  @Column(name = "requested_by", length = 120)
  private String requestedBy;

  @Column(name = "decided_by", length = 120)
  private String decidedBy;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "decided_at")
  private Instant decidedAt;

  @Column(name = "picked_at")
  private Instant pickedAt;

  @Column(name = "finished_at")
  private Instant finishedAt;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "exit_code")
  private Integer exitCode;

  @Column(length = 2000)
  private String output;

  @Column(name = "duration_ms")
  private Long durationMs;

  /** Échéance de la vérification (ADR 0057) : l'alerte doit être résolue d'ici là. */
  @Column(name = "verify_due_at")
  private Instant verifyDueAt;

  @Enumerated(EnumType.STRING)
  @Column(length = 16)
  private RemediationVerification verification;

  @Column(name = "verified_at")
  private Instant verifiedAt;

  /** Pourquoi l'approbation a été imposée à une politique automatique, s'il y a lieu. */
  @Column(name = "approval_reason", length = 200)
  private String approvalReason;

  protected RemediationJob() {}

  public RemediationJob(
      UUID alertId,
      UUID equipmentId,
      UUID agentId,
      UUID ruleId,
      String actionName,
      RemediationTrigger trigger,
      boolean needsApproval,
      String requestedBy,
      Instant now,
      Instant expiresAt) {
    this.id = UUID.randomUUID();
    this.alertId = alertId;
    this.equipmentId = equipmentId;
    this.agentId = agentId;
    this.ruleId = ruleId;
    this.actionName = actionName;
    this.trigger = trigger;
    this.state = needsApproval ? RemediationState.PENDING_APPROVAL : RemediationState.QUEUED;
    this.requestedBy = requestedBy;
    this.createdAt = now;
    this.expiresAt = expiresAt;
  }

  public void approve(String by, Instant at, Instant newExpiry) {
    require(RemediationState.PENDING_APPROVAL);
    state = RemediationState.QUEUED;
    decidedBy = by;
    decidedAt = at;
    expiresAt = newExpiry;
  }

  public void reject(String by, Instant at) {
    require(RemediationState.PENDING_APPROVAL);
    state = RemediationState.REJECTED;
    decidedBy = by;
    decidedAt = at;
  }

  /** L'agent prend l'exécution ; il a jusqu'à {@code reportBy} pour en rendre compte. */
  public void pick(Instant at, Instant reportBy) {
    require(RemediationState.QUEUED);
    state = RemediationState.RUNNING;
    pickedAt = at;
    expiresAt = reportBy;
  }

  public void finish(int code, String text, Long duration, Instant at) {
    require(RemediationState.RUNNING);
    state = code == 0 ? RemediationState.SUCCEEDED : RemediationState.FAILED;
    exitCode = code;
    output = text;
    durationMs = duration;
    finishedAt = at;
  }

  /** L'alerte devra être résolue à cette échéance, sinon la correction sera inefficace. */
  public void scheduleVerification(Instant due) {
    verifyDueAt = due;
  }

  /** Verdict de la vérification (une seule fois). */
  public void verified(RemediationVerification verdict, Instant at) {
    if (verification != null) {
      throw new IllegalStateException("Exécution déjà vérifiée");
    }
    verification = verdict;
    verifiedAt = at;
  }

  public void setApprovalReason(String reason) {
    approvalReason = reason;
  }

  public void expire() {
    if (!state.active()) {
      throw new IllegalStateException("Exécution déjà terminée : " + state);
    }
    state = RemediationState.EXPIRED;
  }

  private void require(RemediationState expected) {
    if (state != expected) {
      throw new IllegalStateException(
          "Transition impossible depuis l'état " + state + " (attendu : " + expected + ")");
    }
  }

  public UUID getId() {
    return id;
  }

  public UUID getAlertId() {
    return alertId;
  }

  public UUID getEquipmentId() {
    return equipmentId;
  }

  public UUID getAgentId() {
    return agentId;
  }

  public UUID getRuleId() {
    return ruleId;
  }

  public String getActionName() {
    return actionName;
  }

  public RemediationState getState() {
    return state;
  }

  public RemediationTrigger getTrigger() {
    return trigger;
  }

  public String getRequestedBy() {
    return requestedBy;
  }

  public String getDecidedBy() {
    return decidedBy;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getDecidedAt() {
    return decidedAt;
  }

  public Instant getPickedAt() {
    return pickedAt;
  }

  public Instant getFinishedAt() {
    return finishedAt;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public Integer getExitCode() {
    return exitCode;
  }

  public String getOutput() {
    return output;
  }

  public Instant getVerifyDueAt() {
    return verifyDueAt;
  }

  public RemediationVerification getVerification() {
    return verification;
  }

  public Instant getVerifiedAt() {
    return verifiedAt;
  }

  public String getApprovalReason() {
    return approvalReason;
  }

  public Long getDurationMs() {
    return durationMs;
  }
}

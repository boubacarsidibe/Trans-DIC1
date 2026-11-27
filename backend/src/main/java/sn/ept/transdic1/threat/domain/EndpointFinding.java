package sn.ept.transdic1.threat.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import sn.ept.transdic1.alerting.domain.Severity;

/**
 * Constat de l'EDR léger (ADR 0058) : un indicateur d'attaque ou un changement suspect sur un
 * poste, rattaché à une technique MITRE ATT&CK, puis jugé par un opérateur. L'équipement est tenu
 * par son identifiant : le module de sécurité ne partage pas les entités de l'inventaire.
 */
@Entity
@Table(name = "endpoint_finding")
public class EndpointFinding {
  @Id private UUID id;

  @Column(name = "equipment_id", nullable = false)
  private UUID equipmentId;

  @Column(nullable = false, length = 40)
  private String rule;

  @Column(name = "technique_id", length = 16)
  private String techniqueId;

  @Column(name = "technique_name", length = 120)
  private String techniqueName;

  @Column(length = 60)
  private String tactic;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private Severity severity;

  @Column(nullable = false, length = 400)
  private String subject;

  @Column(length = 500)
  private String detail;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private EndpointFindingState state;

  @Column(name = "detected_at", nullable = false)
  private Instant detectedAt;

  @Column(name = "last_seen_at", nullable = false)
  private Instant lastSeenAt;

  @Column(name = "decided_by", length = 64)
  private String decidedBy;

  @Column(name = "decided_at")
  private Instant decidedAt;

  protected EndpointFinding() {}

  public EndpointFinding(
      UUID equipmentId,
      String rule,
      String techniqueId,
      String techniqueName,
      String tactic,
      Severity severity,
      String subject,
      String detail,
      Instant at) {
    id = UUID.randomUUID();
    this.equipmentId = equipmentId;
    this.rule = rule;
    this.techniqueId = techniqueId;
    this.techniqueName = techniqueName;
    this.tactic = tactic;
    this.severity = severity;
    this.subject = subject;
    this.detail = detail;
    state = EndpointFindingState.OPEN;
    detectedAt = at;
    lastSeenAt = at;
  }

  public void seenAgain(Instant at) {
    lastSeenAt = at;
  }

  /** Jugement de l'opérateur ; on peut changer d'avis (d'abord légitime, puis malveillant). */
  public void decide(EndpointFindingState verdict, String by, Instant at) {
    if (verdict == EndpointFindingState.OPEN) {
      throw new IllegalArgumentException("Un jugement est LEGITIMATE ou MALICIOUS");
    }
    state = verdict;
    decidedBy = by;
    decidedAt = at;
  }

  public UUID getId() {
    return id;
  }

  public UUID getEquipmentId() {
    return equipmentId;
  }

  public String getRule() {
    return rule;
  }

  public String getTechniqueId() {
    return techniqueId;
  }

  public String getTechniqueName() {
    return techniqueName;
  }

  public String getTactic() {
    return tactic;
  }

  public Severity getSeverity() {
    return severity;
  }

  public String getSubject() {
    return subject;
  }

  public String getDetail() {
    return detail;
  }

  public EndpointFindingState getState() {
    return state;
  }

  public Instant getDetectedAt() {
    return detectedAt;
  }

  public Instant getLastSeenAt() {
    return lastSeenAt;
  }

  public String getDecidedBy() {
    return decidedBy;
  }

  public Instant getDecidedAt() {
    return decidedAt;
  }
}

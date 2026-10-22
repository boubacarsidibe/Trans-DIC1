package sn.ept.transdic1.alerting.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.MonitoredService;

@Entity
@Table(name = "alert")
public class Alert {
  @Id private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "equipment_id", nullable = false)
  private Equipment equipment;

  @ManyToOne(fetch = FetchType.LAZY, optional = true)
  @JoinColumn(name = "service_id")
  private MonitoredService service;

  @ManyToOne(fetch = FetchType.LAZY, optional = true)
  @JoinColumn(name = "rule_id")
  private AlertRule rule;

  @Column(name = "occurrence_key", nullable = false, unique = true, length = 255)
  private String occurrenceKey;

  @Enumerated(EnumType.STRING)
  @Column(name = "type", nullable = false)
  private AlertType type;

  @Enumerated(EnumType.STRING)
  @Column(name = "state", nullable = false)
  private AlertState state;

  @Enumerated(EnumType.STRING)
  @Column(name = "severity", nullable = false)
  private Severity severity;

  @Column(name = "message", nullable = false, length = 2000)
  private String message;

  @Column(name = "triggered_at", nullable = false)
  private Instant triggeredAt;

  @Column(name = "taken_at")
  private Instant takenAt;

  @Column(name = "resolved_at")
  private Instant resolvedAt;

  @Column(name = "archived_at")
  private Instant archivedAt;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  @Enumerated(EnumType.STRING)
  @Column(name = "resolution_type")
  private ResolutionType resolutionType;

  protected Alert() {}

  public Alert(
      Equipment equipment,
      String occurrenceKey,
      AlertType type,
      AlertState state,
      Severity severity,
      String message,
      Instant triggeredAt) {
    id = UUID.randomUUID();
    this.equipment = equipment;
    this.occurrenceKey = occurrenceKey;
    this.type = type;
    this.state = state;
    this.severity = severity;
    this.message = message;
    this.triggeredAt = triggeredAt;
  }

  public UUID getId() {
    return id;
  }

  public Equipment getEquipment() {
    return equipment;
  }

  public MonitoredService getService() {
    return service;
  }

  public void setService(MonitoredService service) {
    this.service = service;
  }

  public AlertRule getRule() {
    return rule;
  }

  public void setRule(AlertRule rule) {
    this.rule = rule;
  }

  public String getOccurrenceKey() {
    return occurrenceKey;
  }

  public void setOccurrenceKey(String occurrenceKey) {
    this.occurrenceKey = occurrenceKey;
  }

  public AlertType getType() {
    return type;
  }

  public void setType(AlertType type) {
    this.type = type;
  }

  public AlertState getState() {
    return state;
  }

  public void setState(AlertState state) {
    this.state = state;
  }

  public Severity getSeverity() {
    return severity;
  }

  public void setSeverity(Severity severity) {
    this.severity = severity;
  }

  public String getMessage() {
    return message;
  }

  public void setMessage(String message) {
    this.message = message;
  }

  public Instant getTriggeredAt() {
    return triggeredAt;
  }

  public void setTriggeredAt(Instant triggeredAt) {
    this.triggeredAt = triggeredAt;
  }

  public Instant getTakenAt() {
    return takenAt;
  }

  public void setTakenAt(Instant takenAt) {
    this.takenAt = takenAt;
  }

  public Instant getResolvedAt() {
    return resolvedAt;
  }

  public void setResolvedAt(Instant resolvedAt) {
    this.resolvedAt = resolvedAt;
  }

  public Instant getArchivedAt() {
    return archivedAt;
  }

  public void setArchivedAt(Instant archivedAt) {
    this.archivedAt = archivedAt;
  }

  public Instant getDeletedAt() {
    return deletedAt;
  }

  public void setDeletedAt(Instant deletedAt) {
    this.deletedAt = deletedAt;
  }

  public ResolutionType getResolutionType() {
    return resolutionType;
  }

  public void setResolutionType(ResolutionType resolutionType) {
    this.resolutionType = resolutionType;
  }
}

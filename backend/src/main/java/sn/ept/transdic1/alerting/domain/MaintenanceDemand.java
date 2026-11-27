package sn.ept.transdic1.alerting.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.security.domain.AppUser;

/**
 * Demande de maintenance (ADR 0058) : l'opérateur NOC la fait, l'administrateur la valide ou la
 * refuse. Tant qu'elle attend, elle ne coupe aucune notification.
 */
@Entity
@Table(name = "maintenance_demand")
public class MaintenanceDemand {
  @Id private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "requester_id", nullable = false)
  private AppUser requester;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "site_id")
  private Site site;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "equipment_id")
  private Equipment equipment;

  @Column(name = "alert_id")
  private UUID alertId;

  @Column(name = "start_at", nullable = false)
  private Instant startAt;

  @Column(name = "end_at", nullable = false)
  private Instant endAt;

  @Column(name = "reason", nullable = false, length = 2000)
  private String reason;

  @Enumerated(EnumType.STRING)
  @Column(name = "state", nullable = false)
  private MaintenanceDemandState state;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "decided_by_id")
  private AppUser decidedBy;

  @Column(name = "decided_at")
  private Instant decidedAt;

  @Column(name = "decision_comment", length = 500)
  private String decisionComment;

  @Column(name = "maintenance_id")
  private UUID maintenanceId;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected MaintenanceDemand() {}

  public MaintenanceDemand(
      AppUser requester, Instant startAt, Instant endAt, String reason, Instant createdAt) {
    id = UUID.randomUUID();
    this.requester = requester;
    this.startAt = startAt;
    this.endAt = endAt;
    this.reason = reason;
    this.createdAt = createdAt;
    state = MaintenanceDemandState.PENDING;
  }

  /** Décision de l'administrateur ; une demande ne se décide qu'une fois. */
  public void decide(
      MaintenanceDemandState decision,
      AppUser by,
      Instant at,
      String comment,
      UUID createdMaintenance) {
    state = decision;
    decidedBy = by;
    decidedAt = at;
    decisionComment = comment;
    maintenanceId = createdMaintenance;
  }

  public UUID getId() {
    return id;
  }

  public AppUser getRequester() {
    return requester;
  }

  public Site getSite() {
    return site;
  }

  public void setSite(Site site) {
    this.site = site;
  }

  public Equipment getEquipment() {
    return equipment;
  }

  public void setEquipment(Equipment equipment) {
    this.equipment = equipment;
  }

  public UUID getAlertId() {
    return alertId;
  }

  public void setAlertId(UUID alertId) {
    this.alertId = alertId;
  }

  public Instant getStartAt() {
    return startAt;
  }

  public Instant getEndAt() {
    return endAt;
  }

  public String getReason() {
    return reason;
  }

  public MaintenanceDemandState getState() {
    return state;
  }

  public AppUser getDecidedBy() {
    return decidedBy;
  }

  public Instant getDecidedAt() {
    return decidedAt;
  }

  public String getDecisionComment() {
    return decisionComment;
  }

  public UUID getMaintenanceId() {
    return maintenanceId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  /** Site de la cible, pour le périmètre d'accès. */
  public UUID targetSiteId() {
    if (site != null) {
      return site.getId();
    }
    return equipment == null || equipment.getSite() == null ? null : equipment.getSite().getId();
  }
}

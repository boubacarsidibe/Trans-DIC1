package sn.ept.transdic1.alerting.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.security.domain.AppUser;

@Entity
@Table(name = "maintenance")
public class Maintenance {
  @Id private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "planner_id", nullable = false)
  private AppUser planner;

  @ManyToOne(fetch = FetchType.LAZY, optional = true)
  @JoinColumn(name = "site_id")
  private Site site;

  @ManyToOne(fetch = FetchType.LAZY, optional = true)
  @JoinColumn(name = "equipment_id")
  private Equipment equipment;

  @Column(name = "start_at", nullable = false)
  private Instant startAt;

  @Column(name = "end_at", nullable = false)
  private Instant endAt;

  @Column(name = "reason", nullable = false, length = 2000)
  private String reason;

  @Enumerated(EnumType.STRING)
  @Column(name = "state", nullable = false)
  private MaintenanceState state;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "cancelled_at")
  private Instant cancelledAt;

  protected Maintenance() {}

  public Maintenance(
      AppUser planner, Instant startAt, Instant endAt, String reason, MaintenanceState state) {
    id = UUID.randomUUID();
    this.planner = planner;
    this.startAt = startAt;
    this.endAt = endAt;
    this.reason = reason;
    this.state = state;
    createdAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public AppUser getPlanner() {
    return planner;
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

  public Instant getStartAt() {
    return startAt;
  }

  public void setStartAt(Instant startAt) {
    this.startAt = startAt;
  }

  public Instant getEndAt() {
    return endAt;
  }

  public void setEndAt(Instant endAt) {
    this.endAt = endAt;
  }

  public String getReason() {
    return reason;
  }

  public void setReason(String reason) {
    this.reason = reason;
  }

  public MaintenanceState getState() {
    return state;
  }

  public void setState(MaintenanceState state) {
    this.state = state;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getCancelledAt() {
    return cancelledAt;
  }

  public void setCancelledAt(Instant cancelledAt) {
    this.cancelledAt = cancelledAt;
  }
}

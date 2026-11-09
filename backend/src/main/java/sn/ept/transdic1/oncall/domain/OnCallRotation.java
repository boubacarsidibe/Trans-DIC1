package sn.ept.transdic1.oncall.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import sn.ept.transdic1.security.domain.AppUser;

/**
 * Rotation d'un planning pour un niveau : les membres se succèdent dans l'ordre, chacun pour {@code
 * shiftLengthDays} jours, la relève ayant lieu à {@code handoverTime} (heure locale du fuseau du
 * planning) à partir de {@code startDate}.
 */
@Entity
@Table(name = "oncall_rotation")
public class OnCallRotation {
  @Id private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "schedule_id", nullable = false)
  private OnCallSchedule schedule;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private OnCallLevel level;

  @Column(name = "start_date", nullable = false)
  private LocalDate startDate;

  @Column(name = "shift_length_days", nullable = false)
  private int shiftLengthDays;

  @Column(name = "handover_time", nullable = false)
  private LocalTime handoverTime;

  @ManyToMany
  @JoinTable(
      name = "oncall_rotation_member",
      joinColumns = @JoinColumn(name = "rotation_id"),
      inverseJoinColumns = @JoinColumn(name = "user_id"))
  @OrderColumn(name = "position")
  private List<AppUser> members = new ArrayList<>();

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected OnCallRotation() {}

  public OnCallRotation(OnCallSchedule schedule, OnCallLevel level, Instant now) {
    id = UUID.randomUUID();
    this.schedule = schedule;
    this.level = level;
    createdAt = now;
    updatedAt = now;
  }

  public UUID getId() {
    return id;
  }

  public OnCallSchedule getSchedule() {
    return schedule;
  }

  public OnCallLevel getLevel() {
    return level;
  }

  public LocalDate getStartDate() {
    return startDate;
  }

  public void setStartDate(LocalDate startDate) {
    this.startDate = startDate;
  }

  public int getShiftLengthDays() {
    return shiftLengthDays;
  }

  public void setShiftLengthDays(int shiftLengthDays) {
    this.shiftLengthDays = shiftLengthDays;
  }

  public LocalTime getHandoverTime() {
    return handoverTime;
  }

  public void setHandoverTime(LocalTime handoverTime) {
    this.handoverTime = handoverTime;
  }

  public List<AppUser> getMembers() {
    return members;
  }

  public void setMembers(List<AppUser> members) {
    this.members.clear();
    this.members.addAll(members);
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void touch(Instant now) {
    updatedAt = now;
  }
}

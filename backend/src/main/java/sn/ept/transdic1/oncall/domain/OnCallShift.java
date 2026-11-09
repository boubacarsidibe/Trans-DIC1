package sn.ept.transdic1.oncall.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import sn.ept.transdic1.security.domain.AppUser;

/**
 * Garde : une personne d'astreinte à un niveau sur la fenêtre [début, fin[ (début inclus, fin
 * exclue). Un remplacement ({@code override}) l'emporte sur une garde normale ; à égalité, la garde
 * créée le plus récemment l'emporte.
 */
@Entity
@Table(name = "oncall_shift")
public class OnCallShift {
  @Id private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "schedule_id", nullable = false)
  private OnCallSchedule schedule;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private AppUser user;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private OnCallLevel level;

  @Column(name = "starts_at", nullable = false)
  private Instant startsAt;

  @Column(name = "ends_at", nullable = false)
  private Instant endsAt;

  @Column(length = 500)
  private String note;

  @Column(nullable = false)
  private boolean override;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "rotation_id")
  private OnCallRotation rotation;

  @Column(name = "replaces_shift_id")
  private UUID replacesShiftId;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "created_by", length = 255)
  private String createdBy;

  protected OnCallShift() {}

  public OnCallShift(
      OnCallSchedule schedule,
      AppUser user,
      OnCallLevel level,
      Instant startsAt,
      Instant endsAt,
      Instant createdAt) {
    id = UUID.randomUUID();
    this.schedule = schedule;
    this.user = user;
    this.level = level;
    this.startsAt = startsAt;
    this.endsAt = endsAt;
    this.createdAt = createdAt;
  }

  /** Fenêtre semi-ouverte : début inclus, fin exclue. */
  public boolean coversInstant(Instant at) {
    return !at.isBefore(startsAt) && at.isBefore(endsAt);
  }

  public UUID getId() {
    return id;
  }

  public OnCallSchedule getSchedule() {
    return schedule;
  }

  public AppUser getUser() {
    return user;
  }

  public void setUser(AppUser user) {
    this.user = user;
  }

  public OnCallLevel getLevel() {
    return level;
  }

  public void setLevel(OnCallLevel level) {
    this.level = level;
  }

  public Instant getStartsAt() {
    return startsAt;
  }

  public void setStartsAt(Instant startsAt) {
    this.startsAt = startsAt;
  }

  public Instant getEndsAt() {
    return endsAt;
  }

  public void setEndsAt(Instant endsAt) {
    this.endsAt = endsAt;
  }

  public String getNote() {
    return note;
  }

  public void setNote(String note) {
    this.note = note;
  }

  public boolean isOverride() {
    return override;
  }

  public void setOverride(boolean override) {
    this.override = override;
  }

  public OnCallRotation getRotation() {
    return rotation;
  }

  public void setRotation(OnCallRotation rotation) {
    this.rotation = rotation;
  }

  public UUID getReplacesShiftId() {
    return replacesShiftId;
  }

  public void setReplacesShiftId(UUID replacesShiftId) {
    this.replacesShiftId = replacesShiftId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public String getCreatedBy() {
    return createdBy;
  }

  public void setCreatedBy(String createdBy) {
    this.createdBy = createdBy;
  }
}

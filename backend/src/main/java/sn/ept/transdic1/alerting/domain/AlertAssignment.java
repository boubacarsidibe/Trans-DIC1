package sn.ept.transdic1.alerting.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import sn.ept.transdic1.security.domain.AppUser;

@Entity
@Table(name = "alert_assignment")
public class AlertAssignment {
  @Id private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "alert_id", nullable = false)
  private Alert alert;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private AppUser user;

  @Column(name = "assigned_at", nullable = false)
  private Instant assignedAt;

  @Column(name = "ended_at")
  private Instant endedAt;

  protected AlertAssignment() {}

  public AlertAssignment(Alert alert, AppUser user, Instant assignedAt) {
    id = UUID.randomUUID();
    this.alert = alert;
    this.user = user;
    this.assignedAt = assignedAt;
  }

  public UUID getId() {
    return id;
  }

  public Alert getAlert() {
    return alert;
  }

  public AppUser getUser() {
    return user;
  }

  public Instant getAssignedAt() {
    return assignedAt;
  }

  public void setAssignedAt(Instant assignedAt) {
    this.assignedAt = assignedAt;
  }

  public Instant getEndedAt() {
    return endedAt;
  }

  public void setEndedAt(Instant endedAt) {
    this.endedAt = endedAt;
  }
}

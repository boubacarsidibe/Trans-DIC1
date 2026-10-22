package sn.ept.transdic1.alerting.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import sn.ept.transdic1.security.domain.AppUser;

@Entity
@Table(name = "alert_event")
public class AlertEvent {
  @Id private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "alert_id", nullable = false)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private Alert alert;

  @ManyToOne(fetch = FetchType.LAZY, optional = true)
  @JoinColumn(name = "author_id")
  private AppUser author;

  @Enumerated(EnumType.STRING)
  @Column(name = "type", nullable = false)
  private AlertEventType type;

  @Enumerated(EnumType.STRING)
  @Column(name = "previous_state")
  private AlertState previousState;

  @Enumerated(EnumType.STRING)
  @Column(name = "new_state")
  private AlertState newState;

  @Column(name = "comment", length = 2000)
  private String comment;

  @Column(name = "occurred_at", nullable = false)
  private Instant occurredAt;

  protected AlertEvent() {}

  public AlertEvent(Alert alert, AlertEventType type, Instant occurredAt) {
    id = UUID.randomUUID();
    this.alert = alert;
    this.type = type;
    this.occurredAt = occurredAt;
  }

  public UUID getId() {
    return id;
  }

  public Alert getAlert() {
    return alert;
  }

  public AppUser getAuthor() {
    return author;
  }

  public void setAuthor(AppUser author) {
    this.author = author;
  }

  public AlertEventType getType() {
    return type;
  }

  public void setType(AlertEventType type) {
    this.type = type;
  }

  public AlertState getPreviousState() {
    return previousState;
  }

  public void setPreviousState(AlertState previousState) {
    this.previousState = previousState;
  }

  public AlertState getNewState() {
    return newState;
  }

  public void setNewState(AlertState newState) {
    this.newState = newState;
  }

  public String getComment() {
    return comment;
  }

  public void setComment(String comment) {
    this.comment = comment;
  }

  public Instant getOccurredAt() {
    return occurredAt;
  }

  public void setOccurredAt(Instant occurredAt) {
    this.occurredAt = occurredAt;
  }
}

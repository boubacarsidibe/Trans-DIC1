package sn.ept.transdic1.reporting.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Entity
@Table(name = "notification_attempt")
public class NotificationAttempt {
  @Id private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "notification_id", nullable = false)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private Notification notification;

  @Column(name = "number", nullable = false)
  private Integer number;

  @Enumerated(EnumType.STRING)
  @Column(name = "result", nullable = false)
  private SendResult result;

  @Column(name = "sanitized_error", length = 1000)
  private String sanitizedError;

  @Column(name = "attempted_at", nullable = false)
  private Instant attemptedAt;

  protected NotificationAttempt() {}

  public NotificationAttempt(
      Notification notification, Integer number, SendResult result, Instant attemptedAt) {
    id = UUID.randomUUID();
    this.notification = notification;
    this.number = number;
    this.result = result;
    this.attemptedAt = attemptedAt;
  }

  public UUID getId() {
    return id;
  }

  public Notification getNotification() {
    return notification;
  }

  public Integer getNumber() {
    return number;
  }

  public void setNumber(Integer number) {
    this.number = number;
  }

  public SendResult getResult() {
    return result;
  }

  public void setResult(SendResult result) {
    this.result = result;
  }

  public String getSanitizedError() {
    return sanitizedError;
  }

  public void setSanitizedError(String sanitizedError) {
    this.sanitizedError = sanitizedError;
  }

  public Instant getAttemptedAt() {
    return attemptedAt;
  }

  public void setAttemptedAt(Instant attemptedAt) {
    this.attemptedAt = attemptedAt;
  }
}

package sn.ept.transdic1.reporting.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import sn.ept.transdic1.alerting.domain.Alert;
import sn.ept.transdic1.security.domain.AppUser;

@Entity
@Table(name = "notification")
public class Notification {
  @Id private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private AppUser user;

  @ManyToOne(fetch = FetchType.LAZY, optional = true)
  @JoinColumn(name = "alert_id")
  private Alert alert;

  @ManyToOne(fetch = FetchType.LAZY, optional = true)
  @JoinColumn(name = "report_id")
  private Report report;

  @Enumerated(EnumType.STRING)
  @Column(name = "channel", nullable = false)
  private NotificationChannel channel;

  @Column(name = "destination_address", nullable = false, length = 254)
  private String destinationAddress;

  @Column(name = "subject", nullable = false, length = 255)
  private String subject;

  @Enumerated(EnumType.STRING)
  @Column(name = "state", nullable = false)
  private NotificationState state;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "sent_at")
  private Instant sentAt;

  protected Notification() {}

  public Notification(
      AppUser user,
      NotificationChannel channel,
      String destinationAddress,
      String subject,
      NotificationState state) {
    id = UUID.randomUUID();
    this.user = user;
    this.channel = channel;
    this.destinationAddress = destinationAddress;
    this.subject = subject;
    this.state = state;
    createdAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public AppUser getUser() {
    return user;
  }

  public Alert getAlert() {
    return alert;
  }

  public void setAlert(Alert alert) {
    this.alert = alert;
  }

  public Report getReport() {
    return report;
  }

  public void setReport(Report report) {
    this.report = report;
  }

  public NotificationChannel getChannel() {
    return channel;
  }

  public void setChannel(NotificationChannel channel) {
    this.channel = channel;
  }

  public String getDestinationAddress() {
    return destinationAddress;
  }

  public void setDestinationAddress(String destinationAddress) {
    this.destinationAddress = destinationAddress;
  }

  public String getSubject() {
    return subject;
  }

  public void setSubject(String subject) {
    this.subject = subject;
  }

  public NotificationState getState() {
    return state;
  }

  public void setState(NotificationState state) {
    this.state = state;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getSentAt() {
    return sentAt;
  }

  public void setSentAt(Instant sentAt) {
    this.sentAt = sentAt;
  }
}

package sn.ept.transdic1.reporting.domain;

import jakarta.persistence.*;
import java.util.UUID;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import sn.ept.transdic1.alerting.domain.Severity;
import sn.ept.transdic1.security.domain.AppUser;

@Entity
@Table(name = "notification_preference")
public class NotificationPreference {
  @Id private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false, unique = true)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private AppUser user;

  @Enumerated(EnumType.STRING)
  @Column(name = "channel", nullable = false)
  private NotificationChannel channel;

  @Enumerated(EnumType.STRING)
  @Column(name = "minimum_severity", nullable = false)
  private Severity minimumSeverity;

  /** Référence du secret contenant l'URL du canal (ex. env:TEAMS_NOC_URL), jamais l'URL. */
  @Column(name = "destination", length = 100)
  private String destination;

  @Column(name = "active", nullable = false)
  private boolean active;

  protected NotificationPreference() {}

  public NotificationPreference(
      AppUser user, NotificationChannel channel, Severity minimumSeverity) {
    id = UUID.randomUUID();
    this.user = user;
    this.channel = channel;
    this.minimumSeverity = minimumSeverity;
    active = true;
  }

  public UUID getId() {
    return id;
  }

  public AppUser getUser() {
    return user;
  }

  public NotificationChannel getChannel() {
    return channel;
  }

  public void setChannel(NotificationChannel channel) {
    this.channel = channel;
  }

  public Severity getMinimumSeverity() {
    return minimumSeverity;
  }

  public void setMinimumSeverity(Severity minimumSeverity) {
    this.minimumSeverity = minimumSeverity;
  }

  public String getDestination() {
    return destination;
  }

  public void setDestination(String destination) {
    this.destination = destination;
  }

  public boolean isActive() {
    return active;
  }

  public void setActive(boolean active) {
    this.active = active;
  }
}

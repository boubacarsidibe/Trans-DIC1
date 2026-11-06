package sn.ept.transdic1.statuspage.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** Mise à jour d'un incident : nouveau statut et message en texte brut. */
@Entity
@Table(name = "status_incident_update")
public class StatusIncidentUpdate {
  @Id private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "incident_id", nullable = false)
  private StatusIncident incident;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private IncidentStatus status;

  @Column(nullable = false, length = 2000)
  private String message;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(length = 255)
  private String author;

  protected StatusIncidentUpdate() {}

  StatusIncidentUpdate(
      StatusIncident incident,
      IncidentStatus status,
      String message,
      String author,
      Instant createdAt) {
    id = UUID.randomUUID();
    this.incident = incident;
    this.status = status;
    this.message = message;
    this.author = author;
    this.createdAt = createdAt;
  }

  public UUID getId() {
    return id;
  }

  public StatusIncident getIncident() {
    return incident;
  }

  public IncidentStatus getStatus() {
    return status;
  }

  public String getMessage() {
    return message;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public String getAuthor() {
    return author;
  }
}

package sn.ept.transdic1.statuspage.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Incident d'une page de statut : titre, statut, impact, message en texte brut, composants touchés
 * et mises à jour successives. Un incident automatique porte le composant qui l'a déclenché.
 */
@Entity
@Table(name = "status_incident")
public class StatusIncident {
  @Id private UUID id;

  @Column(name = "page_id", nullable = false)
  private UUID pageId;

  @Column(nullable = false, length = 160)
  private String title;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private IncidentStatus status;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 8)
  private IncidentImpact impact;

  @Column(nullable = false, length = 2000)
  private String message;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "resolved_at")
  private Instant resolvedAt;

  @Column(length = 255)
  private String author;

  @Column(name = "auto_component_id")
  private UUID autoComponentId;

  @ElementCollection
  @CollectionTable(
      name = "status_incident_component",
      joinColumns = @JoinColumn(name = "incident_id"))
  @Column(name = "component_id")
  private Set<UUID> componentIds = new LinkedHashSet<>();

  @OneToMany(mappedBy = "incident", cascade = CascadeType.ALL, orphanRemoval = true)
  @OrderBy("createdAt ASC")
  private List<StatusIncidentUpdate> updates = new ArrayList<>();

  protected StatusIncident() {}

  public StatusIncident(
      UUID pageId,
      String title,
      IncidentStatus status,
      IncidentImpact impact,
      String message,
      String author,
      Instant now) {
    id = UUID.randomUUID();
    this.pageId = pageId;
    this.title = title;
    this.impact = impact;
    this.message = message;
    this.author = author;
    createdAt = now;
    changeStatus(status, now);
  }

  /** Change le statut ; la date de résolution suit le statut RESOLVED. */
  public void changeStatus(IncidentStatus next, Instant now) {
    if (next == IncidentStatus.RESOLVED && status != IncidentStatus.RESOLVED) {
      resolvedAt = now;
    } else if (next != IncidentStatus.RESOLVED) {
      resolvedAt = null;
    }
    status = next;
  }

  public StatusIncidentUpdate addUpdate(
      IncidentStatus status, String message, String author, Instant now) {
    var update = new StatusIncidentUpdate(this, status, message, author, now);
    updates.add(update);
    changeStatus(status, now);
    return update;
  }

  public UUID getId() {
    return id;
  }

  public UUID getPageId() {
    return pageId;
  }

  public String getTitle() {
    return title;
  }

  public void setTitle(String title) {
    this.title = title;
  }

  public IncidentStatus getStatus() {
    return status;
  }

  public IncidentImpact getImpact() {
    return impact;
  }

  public void setImpact(IncidentImpact impact) {
    this.impact = impact;
  }

  public String getMessage() {
    return message;
  }

  public void setMessage(String message) {
    this.message = message;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getResolvedAt() {
    return resolvedAt;
  }

  public String getAuthor() {
    return author;
  }

  public UUID getAutoComponentId() {
    return autoComponentId;
  }

  public void setAutoComponentId(UUID autoComponentId) {
    this.autoComponentId = autoComponentId;
  }

  public Set<UUID> getComponentIds() {
    return componentIds;
  }

  public void setComponentIds(Set<UUID> componentIds) {
    this.componentIds = componentIds;
  }

  public List<StatusIncidentUpdate> getUpdates() {
    return updates;
  }
}

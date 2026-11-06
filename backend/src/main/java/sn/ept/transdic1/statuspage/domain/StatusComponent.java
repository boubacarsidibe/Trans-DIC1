package sn.ept.transdic1.statuspage.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Composant d'une page de statut : un nom choisi pour les usagers (« Messagerie »), un groupe
 * facultatif et une source supervisée. Le nom technique de la source n'est jamais affiché sur la
 * page.
 */
@Entity
@Table(name = "status_component")
public class StatusComponent {
  @Id private UUID id;

  @Column(name = "page_id", nullable = false)
  private UUID pageId;

  @Column(name = "display_name", nullable = false, length = 80)
  private String displayName;

  @Column(name = "group_name", length = 60)
  private String groupName;

  @Column(nullable = false)
  private int position;

  @Enumerated(EnumType.STRING)
  @Column(name = "source_type", nullable = false, length = 16)
  private ComponentSourceType sourceType;

  @Column(name = "source_id", nullable = false)
  private UUID sourceId;

  /** Début de la panne majeure en cours (incident automatique), nul sinon. */
  @Column(name = "outage_since")
  private Instant outageSince;

  /** Vrai quand l'incident automatique de la panne en cours a déjà été ouvert. */
  @Column(name = "auto_incident_opened", nullable = false)
  private boolean autoIncidentOpened;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected StatusComponent() {}

  public StatusComponent(
      UUID pageId,
      String displayName,
      ComponentSourceType sourceType,
      UUID sourceId,
      int position,
      Instant now) {
    id = UUID.randomUUID();
    this.pageId = pageId;
    this.displayName = displayName;
    this.sourceType = sourceType;
    this.sourceId = sourceId;
    this.position = position;
    createdAt = now;
  }

  public UUID getId() {
    return id;
  }

  public UUID getPageId() {
    return pageId;
  }

  public String getDisplayName() {
    return displayName;
  }

  public void setDisplayName(String displayName) {
    this.displayName = displayName;
  }

  public String getGroupName() {
    return groupName;
  }

  public void setGroupName(String groupName) {
    this.groupName = groupName;
  }

  public int getPosition() {
    return position;
  }

  public void setPosition(int position) {
    this.position = position;
  }

  public ComponentSourceType getSourceType() {
    return sourceType;
  }

  public void setSourceType(ComponentSourceType sourceType) {
    this.sourceType = sourceType;
  }

  public UUID getSourceId() {
    return sourceId;
  }

  public void setSourceId(UUID sourceId) {
    this.sourceId = sourceId;
  }

  public Instant getOutageSince() {
    return outageSince;
  }

  public void setOutageSince(Instant outageSince) {
    this.outageSince = outageSince;
  }

  public boolean isAutoIncidentOpened() {
    return autoIncidentOpened;
  }

  public void setAutoIncidentOpened(boolean autoIncidentOpened) {
    this.autoIncidentOpened = autoIncidentOpened;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}

package sn.ept.transdic1.statuspage.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Page de statut (F2.3, ADR 0026) : un titre, une description et des composants vus par les
 * usagers. Une page publique et active est lisible sans connexion, à l'adresse {@code
 * /statut/{slug}}.
 */
@Entity
@Table(name = "status_page")
public class StatusPage {
  @Id private UUID id;

  @Column(nullable = false, length = 60, unique = true)
  private String slug;

  @Column(nullable = false, length = 120)
  private String title;

  @Column(length = 500)
  private String description;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private StatusVisibility visibility;

  @Column(nullable = false)
  private boolean active;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected StatusPage() {}

  public StatusPage(String slug, String title, Instant now) {
    id = UUID.randomUUID();
    this.slug = slug;
    this.title = title;
    visibility = StatusVisibility.INTERNAL;
    active = true;
    createdAt = now;
    updatedAt = now;
  }

  /** Vrai si la page est lisible sans connexion. */
  public boolean isPubliclyReadable() {
    return active && visibility == StatusVisibility.PUBLIC;
  }

  public UUID getId() {
    return id;
  }

  public String getSlug() {
    return slug;
  }

  public void setSlug(String slug) {
    this.slug = slug;
  }

  public String getTitle() {
    return title;
  }

  public void setTitle(String title) {
    this.title = title;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public StatusVisibility getVisibility() {
    return visibility;
  }

  public void setVisibility(StatusVisibility visibility) {
    this.visibility = visibility;
  }

  public boolean isActive() {
    return active;
  }

  public void setActive(boolean active) {
    this.active = active;
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

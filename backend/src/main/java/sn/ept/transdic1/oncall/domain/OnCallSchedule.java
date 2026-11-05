package sn.ept.transdic1.oncall.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import sn.ept.transdic1.inventory.domain.Site;

/**
 * Planning d'astreinte : couvre tous les sites ou une liste de sites. Le fuseau (IANA) ne sert qu'à
 * placer l'heure de relève des rotations ; toutes les gardes sont stockées en UTC.
 */
@Entity
@Table(name = "oncall_schedule")
public class OnCallSchedule {
  @Id private UUID id;

  @Column(nullable = false, length = 100)
  private String name;

  @Column(length = 500)
  private String description;

  @Column(nullable = false, length = 64)
  private String timezone;

  @Column(name = "all_sites", nullable = false)
  private boolean allSites;

  @Column(nullable = false)
  private boolean active;

  @ManyToMany
  @JoinTable(
      name = "oncall_schedule_site",
      joinColumns = @JoinColumn(name = "schedule_id"),
      inverseJoinColumns = @JoinColumn(name = "site_id"))
  private Set<Site> sites = new LinkedHashSet<>();

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected OnCallSchedule() {}

  public OnCallSchedule(String name, String timezone, Instant now) {
    id = UUID.randomUUID();
    this.name = name;
    this.timezone = timezone;
    allSites = true;
    active = true;
    createdAt = now;
    updatedAt = now;
  }

  /** Vrai si le planning couvre ce site (tous les sites, ou le site est dans la liste). */
  public boolean covers(UUID siteId) {
    return allSites || (siteId != null && sites.stream().anyMatch(s -> s.getId().equals(siteId)));
  }

  public UUID getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public String getTimezone() {
    return timezone;
  }

  public void setTimezone(String timezone) {
    this.timezone = timezone;
  }

  public boolean isAllSites() {
    return allSites;
  }

  public void setAllSites(boolean allSites) {
    this.allSites = allSites;
  }

  public boolean isActive() {
    return active;
  }

  public void setActive(boolean active) {
    this.active = active;
  }

  public Set<Site> getSites() {
    return sites;
  }

  public void setSites(Set<Site> sites) {
    this.sites = sites;
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

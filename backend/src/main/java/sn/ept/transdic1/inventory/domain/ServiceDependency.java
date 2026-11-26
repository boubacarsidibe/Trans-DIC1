package sn.ept.transdic1.inventory.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Un service dépend d'un autre service (l'ENT dépend de PostgreSQL), ADR 0056. Avec « un service
 * tourne sur un équipement » et « un équipement dépend de son amont », c'est la chaîne que parcourt
 * la recherche de cause racine.
 */
@Entity
@Table(name = "service_dependency")
public class ServiceDependency {
  @Id private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "service_id", nullable = false)
  private MonitoredService service;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "depends_on_id", nullable = false)
  private MonitoredService dependsOn;

  @Column(name = "created_by", length = 64)
  private String createdBy;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected ServiceDependency() {}

  public ServiceDependency(
      MonitoredService service, MonitoredService dependsOn, String createdBy, Instant createdAt) {
    id = UUID.randomUUID();
    this.service = service;
    this.dependsOn = dependsOn;
    this.createdBy = createdBy;
    this.createdAt = createdAt;
  }

  public UUID getId() {
    return id;
  }

  public MonitoredService getService() {
    return service;
  }

  public MonitoredService getDependsOn() {
    return dependsOn;
  }

  public String getCreatedBy() {
    return createdBy;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}

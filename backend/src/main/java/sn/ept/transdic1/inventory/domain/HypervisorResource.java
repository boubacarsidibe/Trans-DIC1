package sn.ept.transdic1.inventory.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Un hôte, une VM ou un conteneur vu par un connecteur d'hyperviseur (ADR 0055), et l'équipement
 * qui le représente dans l'inventaire. Jamais supprimé : une ressource disparue est marquée.
 */
@Entity
@Table(name = "hypervisor_resource")
public class HypervisorResource {
  @Id private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "connector_id", nullable = false)
  private HypervisorConnector connector;

  @Column(name = "external_id", nullable = false, length = 160)
  private String externalId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private HypervisorResourceKind kind;

  @Column(nullable = false, length = 120)
  private String name;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "equipment_id")
  private Equipment equipment;

  @Column(name = "host_external_id", length = 160)
  private String hostExternalId;

  @Column(name = "smbios_uuid", length = 64)
  private String smbiosUuid;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private PowerState power;

  @Column(name = "ever_running", nullable = false)
  private boolean everRunning;

  @Column(name = "ip_address", length = 45)
  private String ipAddress;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private HypervisorResourceStatus status;

  @Column(name = "first_seen_at", nullable = false)
  private Instant firstSeenAt;

  @Column(name = "last_seen_at", nullable = false)
  private Instant lastSeenAt;

  @Column(name = "removed_at")
  private Instant removedAt;

  protected HypervisorResource() {}

  public HypervisorResource(
      HypervisorConnector connector,
      String externalId,
      HypervisorResourceKind kind,
      String name,
      Instant now) {
    id = UUID.randomUUID();
    this.connector = connector;
    this.externalId = externalId;
    this.kind = kind;
    this.name = name;
    power = PowerState.UNKNOWN;
    status = HypervisorResourceStatus.PRESENT;
    firstSeenAt = now;
    lastSeenAt = now;
  }

  /**
   * Nouvel état vu par l'hyperviseur. Retourne l'hôte précédent si la VM a changé d'hôte
   * (migration), sinon null.
   */
  public String observe(String name, String hostExternalId, PowerState power, Instant now) {
    String previousHost = this.hostExternalId;
    this.name = name;
    this.power = power;
    this.hostExternalId = hostExternalId;
    if (power == PowerState.RUNNING || power == PowerState.ONLINE) {
      everRunning = true;
    }
    lastSeenAt = now;
    if (status == HypervisorResourceStatus.REMOVED) {
      status = HypervisorResourceStatus.PRESENT;
      removedAt = null;
    }
    boolean moved =
        previousHost != null && hostExternalId != null && !previousHost.equals(hostExternalId);
    return moved ? previousHost : null;
  }

  public void markRemoved(Instant now) {
    status = HypervisorResourceStatus.REMOVED;
    removedAt = now;
  }

  public void attach(Equipment equipment) {
    this.equipment = equipment;
  }

  public void setSmbiosUuid(String smbiosUuid) {
    this.smbiosUuid = smbiosUuid;
  }

  public void setIpAddress(String ipAddress) {
    this.ipAddress = ipAddress;
  }

  public UUID getId() {
    return id;
  }

  public HypervisorConnector getConnector() {
    return connector;
  }

  public String getExternalId() {
    return externalId;
  }

  public HypervisorResourceKind getKind() {
    return kind;
  }

  public String getName() {
    return name;
  }

  public Equipment getEquipment() {
    return equipment;
  }

  public String getHostExternalId() {
    return hostExternalId;
  }

  public String getSmbiosUuid() {
    return smbiosUuid;
  }

  public PowerState getPower() {
    return power;
  }

  public boolean isEverRunning() {
    return everRunning;
  }

  public String getIpAddress() {
    return ipAddress;
  }

  public HypervisorResourceStatus getStatus() {
    return status;
  }

  public Instant getFirstSeenAt() {
    return firstSeenAt;
  }

  public Instant getLastSeenAt() {
    return lastSeenAt;
  }

  public Instant getRemovedAt() {
    return removedAt;
  }
}

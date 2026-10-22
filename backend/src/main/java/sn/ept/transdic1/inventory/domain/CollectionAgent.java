package sn.ept.transdic1.inventory.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "collection_agent")
public class CollectionAgent {
  @Id private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "equipment_id", nullable = false)
  private Equipment equipment;

  @Column(name = "identifier", nullable = false, unique = true, length = 64)
  private String identifier;

  @Column(name = "version", length = 32)
  private String version;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false)
  private AgentStatus status;

  @Column(name = "ip_address", length = 45)
  private String ipAddress;

  @Column(name = "registered_at", nullable = false)
  private Instant registeredAt;

  @Column(name = "last_communication_at")
  private Instant lastCommunicationAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  protected CollectionAgent() {}

  public CollectionAgent(
      Equipment equipment, String identifier, AgentStatus status, Instant registeredAt) {
    id = UUID.randomUUID();
    this.equipment = equipment;
    this.identifier = identifier;
    this.status = status;
    this.registeredAt = registeredAt;
  }

  public UUID getId() {
    return id;
  }

  public Equipment getEquipment() {
    return equipment;
  }

  public String getIdentifier() {
    return identifier;
  }

  public void setIdentifier(String identifier) {
    this.identifier = identifier;
  }

  public String getVersion() {
    return version;
  }

  public void setVersion(String version) {
    this.version = version;
  }

  public AgentStatus getStatus() {
    return status;
  }

  public void setStatus(AgentStatus status) {
    this.status = status;
  }

  public String getIpAddress() {
    return ipAddress;
  }

  public void setIpAddress(String ipAddress) {
    this.ipAddress = ipAddress;
  }

  public Instant getRegisteredAt() {
    return registeredAt;
  }

  public void setRegisteredAt(Instant registeredAt) {
    this.registeredAt = registeredAt;
  }

  public Instant getLastCommunicationAt() {
    return lastCommunicationAt;
  }

  public void setLastCommunicationAt(Instant lastCommunicationAt) {
    this.lastCommunicationAt = lastCommunicationAt;
  }

  public Instant getRevokedAt() {
    return revokedAt;
  }

  public void setRevokedAt(Instant revokedAt) {
    this.revokedAt = revokedAt;
  }
}

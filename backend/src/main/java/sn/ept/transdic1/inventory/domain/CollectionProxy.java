package sn.ept.transdic1.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Relais de collecte d'un site (ADR 0047) : un seul jeton pour tous les équipements du site, et un
 * tampon qui garde les mesures quand la liaison vers le serveur tombe.
 *
 * <p>Même forme qu'un {@link CollectionAgent}, à une différence qui est tout l'objet : un agent est
 * lié à un équipement, un proxy à un site.
 */
@Entity
@Table(name = "collection_proxy")
public class CollectionProxy {
  @Id private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "site_id", nullable = false)
  private Site site;

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

  /** Empreinte SHA-256 du jeton ; le jeton lui-même n'est jamais stocké. */
  @Column(name = "token_hash", length = 128)
  private String tokenHash;

  protected CollectionProxy() {}

  public CollectionProxy(Site site, String identifier, AgentStatus status, Instant registeredAt) {
    id = UUID.randomUUID();
    this.site = site;
    this.identifier = identifier;
    this.status = status;
    this.registeredAt = registeredAt;
  }

  public UUID getId() {
    return id;
  }

  public Site getSite() {
    return site;
  }

  public String getIdentifier() {
    return identifier;
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

  public String getTokenHash() {
    return tokenHash;
  }

  public void setTokenHash(String tokenHash) {
    this.tokenHash = tokenHash;
  }

  /**
   * Dernier signe de vie : la dernière communication, ou l'enregistrement si le proxy ne s'est
   * jamais manifesté — un proxy tout juste enregistré dispose du même délai que les autres.
   */
  public Instant lastSignOfLife() {
    return lastCommunicationAt != null ? lastCommunicationAt : registeredAt;
  }
}

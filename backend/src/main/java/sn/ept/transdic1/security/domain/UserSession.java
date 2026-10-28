package sn.ept.transdic1.security.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Entity
@Table(name = "user_session")
public class UserSession {
  @Id private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private AppUser user;

  @Column(name = "token_hash", nullable = false, unique = true, length = 128)
  private String tokenHash;

  @Column(name = "family_id", nullable = false)
  private UUID familyId;

  @Column(name = "replaced_by_id")
  private UUID replacedById;

  @Column(name = "ip_address", length = 45)
  private String ipAddress;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  protected UserSession() {}

  public UserSession(AppUser user, String tokenHash, Instant expiresAt) {
    this(user, tokenHash, Instant.now(), expiresAt, null);
  }

  /** familyId est nul pour une connexion, et celui de la famille pour une rotation. */
  public UserSession(
      AppUser user, String tokenHash, Instant createdAt, Instant expiresAt, UUID familyId) {
    id = UUID.randomUUID();
    this.user = user;
    this.tokenHash = tokenHash;
    this.createdAt = createdAt;
    this.expiresAt = expiresAt;
    this.familyId = familyId == null ? id : familyId;
  }

  public UUID getFamilyId() {
    return familyId;
  }

  public UUID getReplacedById() {
    return replacedById;
  }

  /** Remplace ce jeton par le suivant : il ne pourra plus être présenté. */
  public void rotate(Instant now, UserSession next) {
    revokedAt = now;
    replacedById = next.id;
  }

  public boolean isExpired(Instant now) {
    return !now.isBefore(expiresAt);
  }

  public UUID getId() {
    return id;
  }

  public AppUser getUser() {
    return user;
  }

  public String getTokenHash() {
    return tokenHash;
  }

  public void setTokenHash(String tokenHash) {
    this.tokenHash = tokenHash;
  }

  public String getIpAddress() {
    return ipAddress;
  }

  public void setIpAddress(String ipAddress) {
    this.ipAddress = ipAddress;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public void setExpiresAt(Instant expiresAt) {
    this.expiresAt = expiresAt;
  }

  public Instant getRevokedAt() {
    return revokedAt;
  }

  public void setRevokedAt(Instant revokedAt) {
    this.revokedAt = revokedAt;
  }
}

package sn.ept.transdic1.security.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Jeton d'API d'un utilisateur : laisse un outil tiers appeler l'API sans compte interactif. Seule
 * l'empreinte est conservée, comme pour les jetons de renouvellement (ADR-0005) : la base ne permet
 * jamais de reconstituer le secret.
 *
 * <p>Le jeton ne porte aucun droit propre : à chaque requête, les droits et le périmètre de son
 * propriétaire sont relus. Désactiver le compte suffit donc à couper ses jetons.
 */
@Entity
@Table(name = "api_token")
public class ApiToken {
  @Id private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private AppUser user;

  @Column(name = "name", nullable = false, length = 120)
  private String name;

  @Column(name = "token_hash", nullable = false, length = 64)
  private String tokenHash;

  /** Début du jeton, pour reconnaître une ligne à l'écran sans montrer le secret. */
  @Column(name = "prefix", nullable = false, length = 16)
  private String prefix;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "expires_at")
  private Instant expiresAt;

  @Column(name = "last_used_at")
  private Instant lastUsedAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  protected ApiToken() {}

  public ApiToken(
      AppUser user,
      String name,
      String tokenHash,
      String prefix,
      Instant createdAt,
      Instant expiresAt) {
    this.id = UUID.randomUUID();
    this.user = user;
    this.name = name;
    this.tokenHash = tokenHash;
    this.prefix = prefix;
    this.createdAt = createdAt;
    this.expiresAt = expiresAt;
  }

  /** Un jeton est utilisable tant qu'il n'est ni révoqué ni expiré. */
  public boolean isUsable(Instant now) {
    return revokedAt == null && (expiresAt == null || expiresAt.isAfter(now));
  }

  public UUID getId() {
    return id;
  }

  public AppUser getUser() {
    return user;
  }

  public String getName() {
    return name;
  }

  public String getPrefix() {
    return prefix;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public Instant getLastUsedAt() {
    return lastUsedAt;
  }

  public void setLastUsedAt(Instant lastUsedAt) {
    this.lastUsedAt = lastUsedAt;
  }

  public Instant getRevokedAt() {
    return revokedAt;
  }

  public void setRevokedAt(Instant revokedAt) {
    this.revokedAt = revokedAt;
  }
}

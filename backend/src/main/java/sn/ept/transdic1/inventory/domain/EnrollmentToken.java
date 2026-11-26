package sn.ept.transdic1.inventory.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Jeton d'inscription partagé (ADR 0054) : il permet à un agent de s'inscrire seul dans un site et
 * de recevoir son propre jeton. Seule l'empreinte SHA-256 est stockée ; le jeton n'est montré
 * qu'une fois, à la création.
 */
@Entity
@Table(name = "enrollment_token")
public class EnrollmentToken {
  @Id private UUID id;

  @Column(nullable = false, length = 120)
  private String name;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "site_id", nullable = false)
  private Site site;

  @Column(name = "token_hash", nullable = false, unique = true, length = 128)
  private String tokenHash;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "max_uses", nullable = false)
  private int maxUses;

  @Column(name = "use_count", nullable = false)
  private int useCount;

  /** Plages CIDR séparées par des virgules ; null = toute adresse. */
  @Column(name = "allowed_ranges", length = 1000)
  private String allowedRanges;

  @Enumerated(EnumType.STRING)
  @Column(name = "approval_mode", nullable = false, length = 16)
  private ApprovalMode approvalMode;

  @Enumerated(EnumType.STRING)
  @Column(name = "equipment_type", nullable = false, length = 32)
  private EquipmentType equipmentType;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private EnrollmentTokenStatus status;

  @Column(name = "created_by", length = 64)
  private String createdBy;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "revoked_by", length = 64)
  private String revokedBy;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  @Column(name = "last_used_at")
  private Instant lastUsedAt;

  protected EnrollmentToken() {}

  public EnrollmentToken(
      String name,
      Site site,
      String tokenHash,
      Instant expiresAt,
      int maxUses,
      String allowedRanges,
      ApprovalMode approvalMode,
      EquipmentType equipmentType,
      String createdBy,
      Instant createdAt) {
    id = UUID.randomUUID();
    this.name = name;
    this.site = site;
    this.tokenHash = tokenHash;
    this.expiresAt = expiresAt;
    this.maxUses = maxUses;
    this.allowedRanges = allowedRanges;
    this.approvalMode = approvalMode;
    this.equipmentType = equipmentType;
    this.createdBy = createdBy;
    this.createdAt = createdAt;
    status = EnrollmentTokenStatus.ACTIVE;
  }

  /** Vrai si le jeton a atteint sa date d'expiration à l'instant donné. */
  public boolean isExpired(Instant now) {
    return !now.isBefore(expiresAt);
  }

  /** Vrai si toutes les utilisations prévues ont été consommées. */
  public boolean isExhausted() {
    return useCount >= maxUses;
  }

  public boolean isRevoked() {
    return status == EnrollmentTokenStatus.REVOKED;
  }

  /** Compte une utilisation (une machine nouvelle, ou une machine qui se réinscrit). */
  public void consume(Instant now) {
    useCount++;
    lastUsedAt = now;
  }

  public void revoke(String by, Instant now) {
    status = EnrollmentTokenStatus.REVOKED;
    revokedBy = by;
    revokedAt = now;
  }

  public UUID getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public Site getSite() {
    return site;
  }

  public String getTokenHash() {
    return tokenHash;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public int getMaxUses() {
    return maxUses;
  }

  public int getUseCount() {
    return useCount;
  }

  public String getAllowedRanges() {
    return allowedRanges;
  }

  public ApprovalMode getApprovalMode() {
    return approvalMode;
  }

  public EquipmentType getEquipmentType() {
    return equipmentType;
  }

  public EnrollmentTokenStatus getStatus() {
    return status;
  }

  public String getCreatedBy() {
    return createdBy;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public String getRevokedBy() {
    return revokedBy;
  }

  public Instant getRevokedAt() {
    return revokedAt;
  }

  public Instant getLastUsedAt() {
    return lastUsedAt;
  }
}

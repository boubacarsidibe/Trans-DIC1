package sn.ept.transdic1.audit.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import sn.ept.transdic1.security.domain.AppUser;

/** Entrée du journal d'audit : append-only, jamais modifiée ni supprimée par l'application. */
@Entity
@Table(name = "audit_entry")
public class AuditEntry {
  @Id private UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "author_id")
  private AppUser author;

  /** Copie du nom du compte au moment de l'action, conservée même si le compte disparaît. */
  @Column(length = 64)
  private String username;

  @Column(nullable = false, length = 80)
  private String action;

  @Column(name = "target_type", nullable = false, length = 80)
  private String targetType;

  @Column(name = "target_id")
  private UUID targetId;

  @Column(name = "correlation_id", length = 64)
  private String correlationId;

  @Column(name = "details_sanitized", length = 1000)
  private String detailsSanitized;

  @Column(name = "performed_at", nullable = false, updatable = false)
  private Instant performedAt;

  protected AuditEntry() {}

  public AuditEntry(
      AppUser author,
      String username,
      String action,
      String targetType,
      UUID targetId,
      String correlationId,
      String detailsSanitized) {
    id = UUID.randomUUID();
    this.author = author;
    this.username = username;
    this.action = action;
    this.targetType = targetType;
    this.targetId = targetId;
    this.correlationId = correlationId;
    this.detailsSanitized = detailsSanitized;
    performedAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public AppUser getAuthor() {
    return author;
  }

  public String getUsername() {
    return username;
  }

  public String getAction() {
    return action;
  }

  public String getTargetType() {
    return targetType;
  }

  public UUID getTargetId() {
    return targetId;
  }

  public String getCorrelationId() {
    return correlationId;
  }

  public String getDetailsSanitized() {
    return detailsSanitized;
  }

  public Instant getPerformedAt() {
    return performedAt;
  }
}

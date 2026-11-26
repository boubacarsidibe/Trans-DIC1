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
 * Demande de redémarrage forcé d'une VM bloquée par l'API de l'hyperviseur (ADR 0057). Toujours
 * soumise à l'approbation d'un administrateur autre que le demandeur ; seule l'action {@code RESET}
 * existe : aucune commande libre ne transite.
 */
@Entity
@Table(name = "hypervisor_action")
public class HypervisorAction {
  /** Seule action possible : l'équivalent du bouton « reset » de la console de l'hyperviseur. */
  public static final String RESET = "RESET";

  @Id private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "resource_id", nullable = false)
  private HypervisorResource resource;

  @Column(nullable = false, length = 16)
  private String action;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private HypervisorActionState state;

  @Column(nullable = false, length = 300)
  private String reason;

  @Column(name = "requested_by", length = 64)
  private String requestedBy;

  @Column(name = "decided_by", length = 64)
  private String decidedBy;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "decided_at")
  private Instant decidedAt;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(length = 500)
  private String result;

  protected HypervisorAction() {}

  public HypervisorAction(
      HypervisorResource resource,
      String reason,
      String requestedBy,
      Instant createdAt,
      Instant expiresAt) {
    id = UUID.randomUUID();
    this.resource = resource;
    action = RESET;
    state = HypervisorActionState.PENDING_APPROVAL;
    this.reason = reason;
    this.requestedBy = requestedBy;
    this.createdAt = createdAt;
    this.expiresAt = expiresAt;
  }

  public boolean isPending() {
    return state == HypervisorActionState.PENDING_APPROVAL;
  }

  /** Décision prise : approuvée puis exécutée (succès ou échec), ou refusée. */
  public void decide(HypervisorActionState outcome, String by, Instant at, String text) {
    if (!isPending()) {
      throw new IllegalStateException("Demande déjà traitée");
    }
    state = outcome;
    decidedBy = by;
    decidedAt = at;
    result = text == null ? null : text.substring(0, Math.min(500, text.length()));
  }

  public void expire(Instant at) {
    if (!isPending()) {
      throw new IllegalStateException("Demande déjà traitée");
    }
    state = HypervisorActionState.EXPIRED;
    decidedAt = at;
  }

  public UUID getId() {
    return id;
  }

  public HypervisorResource getResource() {
    return resource;
  }

  public String getAction() {
    return action;
  }

  public HypervisorActionState getState() {
    return state;
  }

  public String getReason() {
    return reason;
  }

  public String getRequestedBy() {
    return requestedBy;
  }

  public String getDecidedBy() {
    return decidedBy;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getDecidedAt() {
    return decidedAt;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public String getResult() {
    return result;
  }
}

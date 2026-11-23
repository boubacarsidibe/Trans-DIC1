package sn.ept.transdic1.alerting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Lien entre deux équipements <b>appris des alertes</b> (M9, ADR 0042), que la topologie saisie à
 * la main ne connaît pas : un serveur et le stockage qu'il monte, une baie et son onduleur, deux
 * machines derrière le même cordon mal déclaré.
 *
 * <p>Une proposition, jamais un fait. Elle ne sert à rien jusqu'à ce qu'un administrateur l'accepte
 * ; écartée, elle ne reviendra plus. C'est le même principe que les voisins LLDP (ADR 0034), et
 * pour la même raison : un lien inventé à l'envers fausse l'impact d'une panne et le diagnostic, et
 * ici il serait inventé par une statistique, qui ne sait rien des câbles.
 */
@Entity
@Table(name = "learned_correlation")
public class LearnedCorrelation {
  @Id private UUID id;

  /** Le plus petit des deux identifiants : la paire n'existe ainsi qu'une fois. */
  @Column(name = "first_equipment_id", nullable = false)
  private UUID firstEquipmentId;

  @Column(name = "second_equipment_id", nullable = false)
  private UUID secondEquipmentId;

  @Column(name = "together", nullable = false)
  private int together;

  @Column(name = "first_windows", nullable = false)
  private int firstWindows;

  @Column(name = "second_windows", nullable = false)
  private int secondWindows;

  @Column(name = "total_windows", nullable = false)
  private int totalWindows;

  @Column(name = "support", nullable = false, precision = 6, scale = 5)
  private BigDecimal support;

  @Column(name = "confidence", nullable = false, precision = 6, scale = 5)
  private BigDecimal confidence;

  @Enumerated(EnumType.STRING)
  @Column(name = "state", nullable = false)
  private LearnedCorrelationState state;

  @Column(name = "first_seen_at", nullable = false)
  private Instant firstSeenAt;

  @Column(name = "last_computed_at", nullable = false)
  private Instant lastComputedAt;

  @Column(name = "decided_at")
  private Instant decidedAt;

  @Column(name = "decided_by", length = 120)
  private String decidedBy;

  protected LearnedCorrelation() {}

  public LearnedCorrelation(
      UUID firstEquipmentId,
      UUID secondEquipmentId,
      int together,
      int firstWindows,
      int secondWindows,
      int totalWindows,
      BigDecimal support,
      BigDecimal confidence,
      Instant at) {
    this.id = UUID.randomUUID();
    this.firstEquipmentId = firstEquipmentId;
    this.secondEquipmentId = secondEquipmentId;
    this.state = LearnedCorrelationState.PROPOSED;
    this.firstSeenAt = at;
    observe(together, firstWindows, secondWindows, totalWindows, support, confidence, at);
  }

  /** Met à jour les comptes au passage suivant : la proposition vit avec l'historique. */
  public void observe(
      int together,
      int firstWindows,
      int secondWindows,
      int totalWindows,
      BigDecimal support,
      BigDecimal confidence,
      Instant at) {
    this.together = together;
    this.firstWindows = firstWindows;
    this.secondWindows = secondWindows;
    this.totalWindows = totalWindows;
    this.support = support;
    this.confidence = confidence;
    this.lastComputedAt = at;
  }

  public UUID getId() {
    return id;
  }

  public UUID getFirstEquipmentId() {
    return firstEquipmentId;
  }

  public UUID getSecondEquipmentId() {
    return secondEquipmentId;
  }

  public int getTogether() {
    return together;
  }

  public int getFirstWindows() {
    return firstWindows;
  }

  public int getSecondWindows() {
    return secondWindows;
  }

  public int getTotalWindows() {
    return totalWindows;
  }

  public BigDecimal getSupport() {
    return support;
  }

  public BigDecimal getConfidence() {
    return confidence;
  }

  public LearnedCorrelationState getState() {
    return state;
  }

  public Instant getFirstSeenAt() {
    return firstSeenAt;
  }

  public Instant getLastComputedAt() {
    return lastComputedAt;
  }

  public Instant getDecidedAt() {
    return decidedAt;
  }

  public String getDecidedBy() {
    return decidedBy;
  }

  /** Accepte la proposition : le lien appris sert désormais au regroupement en incidents. */
  public void accept(String by, Instant at) {
    decide(LearnedCorrelationState.ACCEPTED, by, at);
  }

  /** Écarte la proposition : elle ne sera plus reproposée, même si la statistique persiste. */
  public void reject(String by, Instant at) {
    decide(LearnedCorrelationState.REJECTED, by, at);
  }

  private void decide(LearnedCorrelationState decided, String by, Instant at) {
    this.state = decided;
    this.decidedBy = by;
    this.decidedAt = at;
  }
}

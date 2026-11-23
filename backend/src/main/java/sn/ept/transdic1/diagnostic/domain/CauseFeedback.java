package sn.ept.transdic1.diagnostic.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Une cause proposée par le diagnostic et le verdict de l'opérateur (M12, ADR 0045) : était-ce la
 * vraie cause ?
 *
 * <p>Une ligne par cause <b>proposée</b>, et non une seule pour celle qui a été retenue. Sans les
 * propositions écartées, on ne saurait pas distinguer « cette source a raison neuf fois sur dix »
 * de « cette source est proposée neuf fois sur dix » : ce sont les refus qui portent l'information.
 *
 * <p>Une confirmation où l'opérateur répond « aucune de ces causes » n'a que des lignes à faux, et
 * c'est précieux : elle dit que le diagnostic s'est trompé sur toute la ligne.
 */
@Entity
@Table(name = "cause_feedback")
public class CauseFeedback {
  @Id private UUID id;

  @Column(name = "alert_id", nullable = false)
  private UUID alertId;

  @Enumerated(EnumType.STRING)
  @Column(name = "source", nullable = false, length = 32)
  private CauseSource source;

  @Column(name = "strength", nullable = false)
  private double strength;

  /**
   * Écart de la preuve à l'ouverture de l'alerte, en minutes ; conservé pour pouvoir un jour
   * apprendre autre chose que le poids par source sans avoir perdu l'historique.
   */
  @Column(name = "minutes_from_opening")
  private Double minutesFromOpening;

  @Column(name = "chosen", nullable = false)
  private boolean chosen;

  @Column(name = "confirmed_at", nullable = false)
  private Instant confirmedAt;

  @Column(name = "confirmed_by", nullable = false, length = 120)
  private String confirmedBy;

  protected CauseFeedback() {}

  public CauseFeedback(
      UUID alertId,
      CauseSource source,
      double strength,
      Double minutesFromOpening,
      boolean chosen,
      Instant confirmedAt,
      String confirmedBy) {
    this.id = UUID.randomUUID();
    this.alertId = alertId;
    this.source = source;
    this.strength = Math.max(0, Math.min(1, strength));
    this.minutesFromOpening = minutesFromOpening;
    this.chosen = chosen;
    this.confirmedAt = confirmedAt;
    this.confirmedBy = confirmedBy;
  }

  public UUID getId() {
    return id;
  }

  public UUID getAlertId() {
    return alertId;
  }

  public CauseSource getSource() {
    return source;
  }

  public double getStrength() {
    return strength;
  }

  public Double getMinutesFromOpening() {
    return minutesFromOpening;
  }

  public boolean isChosen() {
    return chosen;
  }

  public Instant getConfirmedAt() {
    return confirmedAt;
  }

  public String getConfirmedBy() {
    return confirmedBy;
  }
}

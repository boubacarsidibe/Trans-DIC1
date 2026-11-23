package sn.ept.transdic1.alerting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Appartenance d'une alerte à un incident (M10, ADR 0039). L'identifiant de l'alerte est la clé :
 * une alerte appartient à un incident au plus.
 *
 * <p>La raison est conservée en clair, comme {@code correlation_reason} sur une alerte : un
 * regroupement qu'on ne peut pas expliquer n'est pas vérifiable par l'opérateur, et un regroupement
 * qu'il ne peut pas vérifier, il ne lui fera pas confiance.
 */
@Entity
@Table(name = "incident_alert")
public class IncidentAlert {
  @Id
  @Column(name = "alert_id")
  private UUID alertId;

  @Column(name = "incident_id", nullable = false)
  private UUID incidentId;

  @Column(name = "joined_at", nullable = false)
  private Instant joinedAt;

  @Column(name = "reason", nullable = false, length = 300)
  private String reason;

  /**
   * Vrai pour l'alerte qui notifie au nom de l'incident. La décision est prise une fois, au
   * rattachement, et non à la lecture : la file des notifications et l'astreinte sont deux chemins
   * distincts pour la même alerte, et une décision consommée par le premier ferait taire le second.
   */
  @Column(name = "announces", nullable = false)
  private boolean announces;

  protected IncidentAlert() {}

  public IncidentAlert(
      UUID incidentId, UUID alertId, Instant joinedAt, String reason, boolean announces) {
    this.incidentId = incidentId;
    this.alertId = alertId;
    this.joinedAt = joinedAt;
    this.reason = reason;
    this.announces = announces;
  }

  public UUID getAlertId() {
    return alertId;
  }

  public UUID getIncidentId() {
    return incidentId;
  }

  public Instant getJoinedAt() {
    return joinedAt;
  }

  public String getReason() {
    return reason;
  }

  public boolean isAnnounces() {
    return announces;
  }
}

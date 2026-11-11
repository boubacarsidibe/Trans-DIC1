package sn.ept.transdic1.threat.domain;

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
import sn.ept.transdic1.inventory.domain.Equipment;

/**
 * Ligne de journal retenue par la supervision de sécurité : une tentative d'authentification, ou
 * une requête web jugée notable. Les requêtes ordinaires ne sont pas conservées.
 *
 * <p>Contient des données personnelles (adresse de l'appelant, nom de compte visé, URL demandée) :
 * la durée de conservation est volontairement courte et réglable.
 */
@Entity
@Table(name = "security_event")
public class SecurityEvent {
  @Id private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "equipment_id", nullable = false)
  private Equipment equipment;

  @Column(name = "occurred_at", nullable = false)
  private Instant occurredAt;

  @Column(name = "received_at", nullable = false)
  private Instant receivedAt;

  @Enumerated(EnumType.STRING)
  @Column(name = "kind", nullable = false, length = 24)
  private SecurityEventKind kind;

  @Column(name = "source_address", length = 45)
  private String sourceAddress;

  @Column(name = "username", length = 120)
  private String username;

  @Column(name = "method", length = 10)
  private String method;

  @Column(name = "request", length = 2000)
  private String request;

  @Column(name = "status")
  private Integer status;

  @Column(name = "families", length = 255)
  private String families;

  @Column(name = "score", nullable = false)
  private int score;

  protected SecurityEvent() {}

  public SecurityEvent(
      Equipment equipment, SecurityEventKind kind, Instant occurredAt, Instant receivedAt) {
    this.id = UUID.randomUUID();
    this.equipment = equipment;
    this.kind = kind;
    this.occurredAt = occurredAt;
    this.receivedAt = receivedAt;
  }

  public UUID getId() {
    return id;
  }

  public Equipment getEquipment() {
    return equipment;
  }

  public Instant getOccurredAt() {
    return occurredAt;
  }

  public Instant getReceivedAt() {
    return receivedAt;
  }

  public SecurityEventKind getKind() {
    return kind;
  }

  public String getSourceAddress() {
    return sourceAddress;
  }

  public void setSourceAddress(String sourceAddress) {
    this.sourceAddress = sourceAddress;
  }

  public String getUsername() {
    return username;
  }

  public void setUsername(String username) {
    this.username = username;
  }

  public String getMethod() {
    return method;
  }

  public void setMethod(String method) {
    this.method = method;
  }

  public String getRequest() {
    return request;
  }

  public void setRequest(String request) {
    this.request = request;
  }

  public Integer getStatus() {
    return status;
  }

  public void setStatus(Integer status) {
    this.status = status;
  }

  public String getFamilies() {
    return families;
  }

  public void setFamilies(String families) {
    this.families = families;
  }

  public int getScore() {
    return score;
  }

  public void setScore(int score) {
    this.score = score;
  }
}

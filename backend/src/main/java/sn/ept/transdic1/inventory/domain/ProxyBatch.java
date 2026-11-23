package sn.ept.transdic1.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Lot déjà reçu d'un proxy (ADR 0047), gardé pour qu'un rejeu ne compte pas deux fois.
 *
 * <p>Un proxy qui vide son tampon et perd l'accusé de réception renvoie le même lot : sans cette
 * trace, les mesures seraient stockées en double et les moyennes horaires fausses. Le bilan du
 * premier passage est conservé, et c'est lui qu'on renvoie.
 */
@Entity
@Table(name = "proxy_batch")
public class ProxyBatch {
  @Id private UUID id;

  @Column(name = "proxy_id", nullable = false)
  private UUID proxyId;

  @Column(name = "lot_id", nullable = false)
  private UUID lotId;

  @Column(name = "received_at", nullable = false)
  private Instant receivedAt;

  @Column(name = "stored", nullable = false)
  private int stored;

  @Column(name = "evaluated", nullable = false)
  private int evaluated;

  @Column(name = "alerts_opened", nullable = false)
  private int alertsOpened;

  @Column(name = "alerts_closed", nullable = false)
  private int alertsClosed;

  protected ProxyBatch() {}

  public ProxyBatch(
      UUID proxyId,
      UUID lotId,
      Instant receivedAt,
      int stored,
      int evaluated,
      int alertsOpened,
      int alertsClosed) {
    id = UUID.randomUUID();
    this.proxyId = proxyId;
    this.lotId = lotId;
    this.receivedAt = receivedAt;
    this.stored = stored;
    this.evaluated = evaluated;
    this.alertsOpened = alertsOpened;
    this.alertsClosed = alertsClosed;
  }

  public UUID getId() {
    return id;
  }

  public UUID getProxyId() {
    return proxyId;
  }

  public UUID getLotId() {
    return lotId;
  }

  public Instant getReceivedAt() {
    return receivedAt;
  }

  public int getStored() {
    return stored;
  }

  public int getEvaluated() {
    return evaluated;
  }

  public int getAlertsOpened() {
    return alertsOpened;
  }

  public int getAlertsClosed() {
    return alertsClosed;
  }
}

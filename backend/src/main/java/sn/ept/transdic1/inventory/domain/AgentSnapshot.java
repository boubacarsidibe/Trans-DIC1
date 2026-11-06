package sn.ept.transdic1.inventory.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** Dernier inventaire et top des processus envoyés par l'agent d'un équipement (JSON). */
@Entity
@Table(name = "agent_snapshot")
public class AgentSnapshot {
  @Id
  @Column(name = "equipment_id")
  private UUID equipmentId;

  @Column(nullable = false, length = 20000)
  private String payload;

  @Column(name = "received_at", nullable = false)
  private Instant receivedAt;

  protected AgentSnapshot() {}

  public AgentSnapshot(UUID equipmentId, String payload, Instant receivedAt) {
    this.equipmentId = equipmentId;
    this.payload = payload;
    this.receivedAt = receivedAt;
  }

  public void replace(String payload, Instant receivedAt) {
    this.payload = payload;
    this.receivedAt = receivedAt;
  }

  public UUID getEquipmentId() {
    return equipmentId;
  }

  public String getPayload() {
    return payload;
  }

  public Instant getReceivedAt() {
    return receivedAt;
  }
}

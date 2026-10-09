package sn.ept.transdic1.monitoring.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import sn.ept.transdic1.inventory.domain.Equipment;

@Entity
public class Metric {
  @Id private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "equipment_id")
  private Equipment equipment;

  @Column(name = "metric_key", nullable = false, length = 64)
  private String key;

  @Column(name = "metric_value", nullable = false, precision = 12, scale = 4)
  private BigDecimal value;

  @Column(nullable = false, length = 24)
  private String unit;

  @Column(nullable = false, length = 24)
  private String quality;

  @Column(name = "collected_at", nullable = false)
  private Instant collectedAt;

  @Column(name = "received_at", nullable = false)
  private Instant receivedAt;

  protected Metric() {}

  public UUID getId() {
    return id;
  }

  public Equipment getEquipment() {
    return equipment;
  }

  public String getKey() {
    return key;
  }

  public BigDecimal getValue() {
    return value;
  }

  public String getUnit() {
    return unit;
  }

  public String getQuality() {
    return quality;
  }

  public Instant getCollectedAt() {
    return collectedAt;
  }
}

package sn.ept.transdic1.monitoring.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import sn.ept.transdic1.inventory.domain.CollectionAgent;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.MetricDefinition;
import sn.ept.transdic1.inventory.domain.MonitoredService;

@Entity
public class Metric {
  @Id private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "equipment_id")
  private Equipment equipment;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "definition_id")
  private MetricDefinition definition;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "service_id")
  private MonitoredService service;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "agent_id")
  private CollectionAgent agent;

  @Column(name = "metric_key", nullable = false, length = 64)
  private String key;

  @Column(name = "metric_value", nullable = false, precision = 12, scale = 4)
  private BigDecimal value;

  @Column(nullable = false, length = 24)
  private String unit;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 24)
  private MeasurementQuality quality;

  @Column(name = "collected_at", nullable = false)
  private Instant collectedAt;

  @Column(name = "received_at", nullable = false)
  private Instant receivedAt;

  protected Metric() {}

  public Metric(
      Equipment equipment,
      String key,
      BigDecimal value,
      String unit,
      MeasurementQuality quality,
      Instant collectedAt) {
    id = UUID.randomUUID();
    this.equipment = equipment;
    this.key = key;
    this.value = value;
    this.unit = unit;
    this.quality = quality;
    this.collectedAt = collectedAt;
    receivedAt = Instant.now();
  }

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

  public MeasurementQuality getQuality() {
    return quality;
  }

  public Instant getCollectedAt() {
    return collectedAt;
  }

  public MetricDefinition getDefinition() {
    return definition;
  }

  public void setDefinition(MetricDefinition definition) {
    this.definition = definition;
  }

  public MonitoredService getService() {
    return service;
  }

  public void setService(MonitoredService service) {
    this.service = service;
  }

  public CollectionAgent getAgent() {
    return agent;
  }

  public void setAgent(CollectionAgent agent) {
    this.agent = agent;
  }

  public Instant getReceivedAt() {
    return receivedAt;
  }
}

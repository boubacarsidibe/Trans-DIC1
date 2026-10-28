package sn.ept.transdic1.monitoring.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import sn.ept.transdic1.inventory.domain.CollectionAgent;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.MetricDefinition;
import sn.ept.transdic1.inventory.domain.MonitoredService;

@Entity
public class Metric {
  /**
   * Identifiant entier issu de metric_id_seq (ADR-0004). La clé primaire en base est (collected_at,
   * id) car la table est partitionnée par mois sur collected_at.
   */
  @Id
  @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "metric_id_seq")
  @SequenceGenerator(name = "metric_id_seq", sequenceName = "metric_id_seq", allocationSize = 1)
  private Long id;

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
    this.equipment = equipment;
    this.key = key;
    this.value = value;
    this.unit = unit;
    this.quality = quality;
    this.collectedAt = collectedAt;
    receivedAt = Instant.now();
  }

  public Long getId() {
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

package sn.ept.transdic1.inventory.domain;

import jakarta.persistence.*;
import java.util.UUID;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Entity
@Table(name = "metric_definition")
public class MetricDefinition {
  @Id private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "configuration_id", nullable = false)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private CollectionConfiguration configuration;

  @Column(name = "name", nullable = false, length = 120)
  private String name;

  @Column(name = "technical_key", nullable = false, length = 64)
  private String technicalKey;

  @Enumerated(EnumType.STRING)
  @Column(name = "value_type", nullable = false)
  private ValueType valueType;

  @Column(name = "unit", length = 24)
  private String unit;

  @Column(name = "snmp_oid", length = 255)
  private String snmpOid;

  @Column(name = "agent_command", length = 255)
  private String agentCommand;

  @Column(name = "active", nullable = false)
  private boolean active;

  protected MetricDefinition() {}

  public MetricDefinition(
      CollectionConfiguration configuration,
      String name,
      String technicalKey,
      ValueType valueType) {
    id = UUID.randomUUID();
    this.configuration = configuration;
    this.name = name;
    this.technicalKey = technicalKey;
    this.valueType = valueType;
    active = true;
  }

  public UUID getId() {
    return id;
  }

  public CollectionConfiguration getConfiguration() {
    return configuration;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getTechnicalKey() {
    return technicalKey;
  }

  public void setTechnicalKey(String technicalKey) {
    this.technicalKey = technicalKey;
  }

  public ValueType getValueType() {
    return valueType;
  }

  public void setValueType(ValueType valueType) {
    this.valueType = valueType;
  }

  public String getUnit() {
    return unit;
  }

  public void setUnit(String unit) {
    this.unit = unit;
  }

  public String getSnmpOid() {
    return snmpOid;
  }

  public void setSnmpOid(String snmpOid) {
    this.snmpOid = snmpOid;
  }

  public String getAgentCommand() {
    return agentCommand;
  }

  public void setAgentCommand(String agentCommand) {
    this.agentCommand = agentCommand;
  }

  public boolean isActive() {
    return active;
  }

  public void setActive(boolean active) {
    this.active = active;
  }
}

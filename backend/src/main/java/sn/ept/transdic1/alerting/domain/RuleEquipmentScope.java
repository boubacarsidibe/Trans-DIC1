package sn.ept.transdic1.alerting.domain;

import jakarta.persistence.*;
import java.util.UUID;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import sn.ept.transdic1.inventory.domain.Equipment;

@Entity
@Table(name = "rule_equipment_scope")
public class RuleEquipmentScope {
  @Id private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "rule_id", nullable = false)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private AlertRule rule;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "equipment_id", nullable = false)
  private Equipment equipment;

  @Column(name = "active", nullable = false)
  private boolean active;

  protected RuleEquipmentScope() {}

  public RuleEquipmentScope(AlertRule rule, Equipment equipment) {
    id = UUID.randomUUID();
    this.rule = rule;
    this.equipment = equipment;
    active = true;
  }

  public UUID getId() {
    return id;
  }

  public AlertRule getRule() {
    return rule;
  }

  public Equipment getEquipment() {
    return equipment;
  }

  public boolean isActive() {
    return active;
  }

  public void setActive(boolean active) {
    this.active = active;
  }
}

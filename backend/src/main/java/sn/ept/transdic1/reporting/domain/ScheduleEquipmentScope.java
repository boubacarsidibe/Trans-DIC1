package sn.ept.transdic1.reporting.domain;

import jakarta.persistence.*;
import java.util.UUID;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import sn.ept.transdic1.inventory.domain.Equipment;

@Entity
@Table(name = "schedule_equipment_scope")
public class ScheduleEquipmentScope {
  @Id private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "schedule_id", nullable = false)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private ReportSchedule schedule;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "equipment_id", nullable = false)
  private Equipment equipment;

  protected ScheduleEquipmentScope() {}

  public ScheduleEquipmentScope(ReportSchedule schedule, Equipment equipment) {
    id = UUID.randomUUID();
    this.schedule = schedule;
    this.equipment = equipment;
  }

  public UUID getId() {
    return id;
  }

  public ReportSchedule getSchedule() {
    return schedule;
  }

  public Equipment getEquipment() {
    return equipment;
  }
}

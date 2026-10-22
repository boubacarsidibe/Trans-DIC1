package sn.ept.transdic1.reporting.domain;

import jakarta.persistence.*;
import java.util.UUID;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import sn.ept.transdic1.security.domain.AppUser;

@Entity
@Table(name = "schedule_recipient")
public class ScheduleRecipient {
  @Id private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "schedule_id", nullable = false)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private ReportSchedule schedule;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private AppUser user;

  protected ScheduleRecipient() {}

  public ScheduleRecipient(ReportSchedule schedule, AppUser user) {
    id = UUID.randomUUID();
    this.schedule = schedule;
    this.user = user;
  }

  public UUID getId() {
    return id;
  }

  public ReportSchedule getSchedule() {
    return schedule;
  }

  public AppUser getUser() {
    return user;
  }
}

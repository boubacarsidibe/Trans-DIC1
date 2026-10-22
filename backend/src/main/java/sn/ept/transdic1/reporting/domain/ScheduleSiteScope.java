package sn.ept.transdic1.reporting.domain;

import jakarta.persistence.*;
import java.util.UUID;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import sn.ept.transdic1.inventory.domain.Site;

@Entity
@Table(name = "schedule_site_scope")
public class ScheduleSiteScope {
  @Id private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "schedule_id", nullable = false)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private ReportSchedule schedule;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "site_id", nullable = false)
  private Site site;

  protected ScheduleSiteScope() {}

  public ScheduleSiteScope(ReportSchedule schedule, Site site) {
    id = UUID.randomUUID();
    this.schedule = schedule;
    this.site = site;
  }

  public UUID getId() {
    return id;
  }

  public ReportSchedule getSchedule() {
    return schedule;
  }

  public Site getSite() {
    return site;
  }
}

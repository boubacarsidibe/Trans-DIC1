package sn.ept.transdic1.reporting.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;
import sn.ept.transdic1.security.domain.AppUser;

@Entity
@Table(name = "report_schedule")
public class ReportSchedule {
  @Id private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "creator_id", nullable = false)
  private AppUser creator;

  @Column(name = "name", nullable = false, length = 120)
  private String name;

  @Enumerated(EnumType.STRING)
  @Column(name = "report_type", nullable = false)
  private ReportType reportType;

  @Enumerated(EnumType.STRING)
  @Column(name = "frequency", nullable = false)
  private ReportFrequency frequency;

  @Column(name = "execution_time", nullable = false)
  private LocalTime executionTime;

  @Column(name = "active", nullable = false)
  private boolean active;

  @Column(name = "next_run_at")
  private Instant nextRunAt;

  protected ReportSchedule() {}

  public ReportSchedule(
      AppUser creator,
      String name,
      ReportType reportType,
      ReportFrequency frequency,
      LocalTime executionTime) {
    id = UUID.randomUUID();
    this.creator = creator;
    this.name = name;
    this.reportType = reportType;
    this.frequency = frequency;
    this.executionTime = executionTime;
    active = true;
  }

  public UUID getId() {
    return id;
  }

  public AppUser getCreator() {
    return creator;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public ReportType getReportType() {
    return reportType;
  }

  public void setReportType(ReportType reportType) {
    this.reportType = reportType;
  }

  public ReportFrequency getFrequency() {
    return frequency;
  }

  public void setFrequency(ReportFrequency frequency) {
    this.frequency = frequency;
  }

  public LocalTime getExecutionTime() {
    return executionTime;
  }

  public void setExecutionTime(LocalTime executionTime) {
    this.executionTime = executionTime;
  }

  public boolean isActive() {
    return active;
  }

  public void setActive(boolean active) {
    this.active = active;
  }

  public Instant getNextRunAt() {
    return nextRunAt;
  }

  public void setNextRunAt(Instant nextRunAt) {
    this.nextRunAt = nextRunAt;
  }
}

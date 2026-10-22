package sn.ept.transdic1.reporting.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import sn.ept.transdic1.security.domain.AppUser;

@Entity
@Table(name = "report")
public class Report {
  @Id private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "requester_id", nullable = false)
  private AppUser requester;

  @ManyToOne(fetch = FetchType.LAZY, optional = true)
  @JoinColumn(name = "schedule_id")
  private ReportSchedule schedule;

  @Enumerated(EnumType.STRING)
  @Column(name = "type", nullable = false)
  private ReportType type;

  @Column(name = "period_start", nullable = false)
  private Instant periodStart;

  @Column(name = "period_end", nullable = false)
  private Instant periodEnd;

  @Enumerated(EnumType.STRING)
  @Column(name = "state", nullable = false)
  private ReportState state;

  @Enumerated(EnumType.STRING)
  @Column(name = "format", nullable = false)
  private ReportFormat format;

  @Column(name = "file_path", length = 512)
  private String filePath;

  @Column(name = "generated_at")
  private Instant generatedAt;

  protected Report() {}

  public Report(
      AppUser requester,
      ReportType type,
      Instant periodStart,
      Instant periodEnd,
      ReportState state,
      ReportFormat format) {
    id = UUID.randomUUID();
    this.requester = requester;
    this.type = type;
    this.periodStart = periodStart;
    this.periodEnd = periodEnd;
    this.state = state;
    this.format = format;
  }

  public UUID getId() {
    return id;
  }

  public AppUser getRequester() {
    return requester;
  }

  public ReportSchedule getSchedule() {
    return schedule;
  }

  public void setSchedule(ReportSchedule schedule) {
    this.schedule = schedule;
  }

  public ReportType getType() {
    return type;
  }

  public void setType(ReportType type) {
    this.type = type;
  }

  public Instant getPeriodStart() {
    return periodStart;
  }

  public void setPeriodStart(Instant periodStart) {
    this.periodStart = periodStart;
  }

  public Instant getPeriodEnd() {
    return periodEnd;
  }

  public void setPeriodEnd(Instant periodEnd) {
    this.periodEnd = periodEnd;
  }

  public ReportState getState() {
    return state;
  }

  public void setState(ReportState state) {
    this.state = state;
  }

  public ReportFormat getFormat() {
    return format;
  }

  public void setFormat(ReportFormat format) {
    this.format = format;
  }

  public String getFilePath() {
    return filePath;
  }

  public void setFilePath(String filePath) {
    this.filePath = filePath;
  }

  public Instant getGeneratedAt() {
    return generatedAt;
  }

  public void setGeneratedAt(Instant generatedAt) {
    this.generatedAt = generatedAt;
  }
}

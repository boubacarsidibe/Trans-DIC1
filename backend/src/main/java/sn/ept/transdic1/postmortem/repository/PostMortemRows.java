package sn.ept.transdic1.postmortem.repository;

import java.time.Instant;
import java.util.UUID;

/** Lignes lues par {@link PostMortemRepository} : des faits bruts, sans mise en forme. */
public final class PostMortemRows {
  private PostMortemRows() {}

  public record IncidentRow(
      UUID id,
      String title,
      String severity,
      String state,
      Instant openedAt,
      Instant lastAlertAt,
      Instant resolvedAt,
      UUID siteId,
      String siteName) {}

  public record AlertRow(
      UUID id,
      UUID equipmentId,
      String equipmentName,
      String equipmentType,
      UUID siteId,
      String type,
      String severity,
      String state,
      String message,
      Instant triggeredAt,
      Instant takenAt,
      Instant resolvedAt,
      String resolutionType,
      Instant escalatedAt,
      String reason,
      boolean announces,
      String ruleName) {}

  public record EventRow(
      UUID alertId, String type, String newState, String comment, Instant at, String author) {}

  public record NotificationRow(
      UUID alertId,
      String channel,
      String state,
      String kind,
      Instant createdAt,
      Instant sentAt,
      String recipient,
      int attempts) {}

  public record OnCallRow(UUID alertId, String level, String outcome, Instant at, String user) {}

  public record CauseRow(UUID alertId, String source, Instant confirmedAt, String confirmedBy) {}

  public record RemediationRow(
      UUID alertId,
      String action,
      String state,
      String trigger,
      Instant createdAt,
      Instant finishedAt,
      Integer exitCode,
      String requestedBy) {}

  public record MaintenanceRow(String reason, Instant startAt, Instant endAt, String target) {}

  public record ComponentRow(String component, String page, boolean publicPage) {}

  /** Une alerte de la période, pour les indicateurs de service. */
  public record KpiAlertRow(
      UUID id,
      String severity,
      String type,
      Instant triggeredAt,
      Instant takenAt,
      Instant resolvedAt,
      String resolutionType,
      UUID siteId,
      String siteName,
      UUID equipmentId,
      String equipmentName,
      String taker) {}
}

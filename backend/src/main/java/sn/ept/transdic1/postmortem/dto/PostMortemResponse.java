package sn.ept.transdic1.postmortem.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Post-mortem d'un incident : ce qui s'est passé, qui a fait quoi et quand, ce que la panne a
 * touché, la cause retenue, et ce qu'il faut en retenir. Tout vient de la base, rien n'est deviné.
 */
@Schema(description = "Post-mortem d'un incident, reconstitué à partir de la base")
public record PostMortemResponse(
    Summary incident,
    Durations durations,
    RootAlert root,
    List<AlertLine> alerts,
    Impact impact,
    Cause cause,
    List<TimelineEntry> timeline,
    List<Finding> findings,
    List<String> responders,
    Instant generatedAt) {

  public record Summary(
      UUID id,
      String title,
      String severity,
      String state,
      String siteName,
      Instant openedAt,
      Instant resolvedAt) {}

  @Schema(description = "Durées en minutes ; nulles tant que l'étape n'a pas eu lieu")
  public record Durations(
      Double minutesToAcknowledge,
      Double minutesToResolve,
      Double minutesFromAcknowledgeToResolve,
      boolean acknowledged,
      boolean resolved,
      int acknowledgeTargetMinutes) {}

  public record RootAlert(
      UUID alertId, String equipmentName, String equipmentType, String message, Instant at) {}

  public record AlertLine(
      UUID id,
      String equipmentName,
      String type,
      String severity,
      String state,
      String message,
      Instant triggeredAt,
      Instant takenAt,
      Instant resolvedAt,
      String resolutionType,
      String reason,
      boolean announces) {}

  public record Component(String name, String page, boolean publicPage) {}

  public record Impact(
      int equipmentCount,
      List<String> equipments,
      List<Component> statusComponents,
      int notificationsSent,
      int notificationsFailed,
      int notificationsAvoided,
      int publicCommunications) {}

  @Schema(description = "Cause confirmée par un opérateur, ou absente")
  public record Cause(
      boolean confirmed, String source, String label, String confirmedBy, Instant confirmedAt) {}

  @Schema(
      description =
          "Étape de la chronologie. kind : INCIDENT_OPENED, ALERT_OPENED, NOTIFIED, ONCALL,"
              + " ESCALATED, TAKEN, ASSIGNED, COMMENTED, CORRELATED, SEVERITY_CHANGED,"
              + " REMEDIATION, CAUSE_CONFIRMED, MAINTENANCE, RESOLVED, INCIDENT_RESOLVED")
  public record TimelineEntry(Instant at, String kind, String title, String detail, String actor) {}

  @Schema(description = "Constat tiré des faits. level : POSITIVE, WARNING ou ACTION")
  public record Finding(String level, String text, String recommendation) {}
}

package sn.ept.transdic1.alerting.dto;

import java.time.Instant;
import java.util.UUID;
import sn.ept.transdic1.alerting.domain.Alert;
import sn.ept.transdic1.alerting.domain.AlertState;
import sn.ept.transdic1.alerting.domain.AlertType;
import sn.ept.transdic1.alerting.domain.Severity;

/** Une alerte telle que l'affiche la liste du NOC. */
public record AlertResponse(
    UUID id,
    UUID equipmentId,
    String equipmentName,
    String ruleName,
    AlertType type,
    AlertState state,
    Severity severity,
    String message,
    Instant triggeredAt,
    Instant takenAt,
    Instant resolvedAt,
    String assignee,
    java.util.List<String> assignees) {

  /** {@code assignee} reste le premier assigné, pour les clients qui n'en affichent qu'un. */
  public static AlertResponse from(Alert alert, java.util.List<String> assignees) {
    return new AlertResponse(
        alert.getId(),
        alert.getEquipment().getId(),
        alert.getEquipment().getName(),
        alert.getRule() == null ? null : alert.getRule().getName(),
        alert.getType(),
        alert.getState(),
        alert.getSeverity(),
        alert.getMessage(),
        alert.getTriggeredAt(),
        alert.getTakenAt(),
        alert.getResolvedAt(),
        assignees.isEmpty() ? null : assignees.get(0),
        java.util.List.copyOf(assignees));
  }
}

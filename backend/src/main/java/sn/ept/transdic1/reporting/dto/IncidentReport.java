package sn.ept.transdic1.reporting.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import sn.ept.transdic1.alerting.domain.AlertState;
import sn.ept.transdic1.alerting.domain.AlertType;
import sn.ept.transdic1.alerting.domain.Severity;

/**
 * Alertes déclenchées pendant une période : volumes, délai moyen de résolution, équipements les
 * plus touchés.
 */
public record IncidentReport(
    Instant from,
    Instant to,
    Instant generatedAt,
    long total,
    long resolved,
    Long meanTimeToResolveSeconds,
    Map<Severity, Long> bySeverity,
    List<EquipmentCount> topEquipments,
    List<Row> incidents) {

  /** Nombre d'alertes d'un équipement. */
  public record EquipmentCount(UUID equipmentId, String equipmentName, long count) {}

  public record Row(
      UUID alertId,
      String equipmentName,
      AlertType type,
      Severity severity,
      AlertState state,
      String message,
      Instant triggeredAt,
      Instant resolvedAt) {}
}

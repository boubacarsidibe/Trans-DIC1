package sn.ept.transdic1.alerting.dto;

import java.time.Instant;
import java.util.UUID;
import sn.ept.transdic1.alerting.domain.MaintenanceDemand;
import sn.ept.transdic1.alerting.domain.MaintenanceDemandState;

/** Une demande de maintenance, avec sa décision. */
public record MaintenanceDemandResponse(
    UUID id,
    UUID siteId,
    String siteName,
    UUID equipmentId,
    String equipmentName,
    UUID alertId,
    Instant startAt,
    Instant endAt,
    String reason,
    MaintenanceDemandState state,
    String requester,
    Instant createdAt,
    String decidedBy,
    Instant decidedAt,
    String decisionComment,
    UUID maintenanceId) {

  public static MaintenanceDemandResponse from(MaintenanceDemand d) {
    return new MaintenanceDemandResponse(
        d.getId(),
        d.getSite() == null ? null : d.getSite().getId(),
        d.getSite() == null ? null : d.getSite().getName(),
        d.getEquipment() == null ? null : d.getEquipment().getId(),
        d.getEquipment() == null ? null : d.getEquipment().getName(),
        d.getAlertId(),
        d.getStartAt(),
        d.getEndAt(),
        d.getReason(),
        d.getState(),
        d.getRequester().getUsername(),
        d.getCreatedAt(),
        d.getDecidedBy() == null ? null : d.getDecidedBy().getUsername(),
        d.getDecidedAt(),
        d.getDecisionComment(),
        d.getMaintenanceId());
  }
}

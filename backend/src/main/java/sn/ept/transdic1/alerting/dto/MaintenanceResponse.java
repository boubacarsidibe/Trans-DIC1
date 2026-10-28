package sn.ept.transdic1.alerting.dto;

import java.time.Instant;
import java.util.UUID;
import sn.ept.transdic1.alerting.domain.Maintenance;
import sn.ept.transdic1.alerting.domain.MaintenanceState;

/**
 * Une maintenance ; l'état est calculé à l'instant de la lecture (annulée, à venir, en cours,
 * terminée).
 */
public record MaintenanceResponse(
    UUID id,
    UUID siteId,
    String siteName,
    UUID equipmentId,
    String equipmentName,
    Instant startAt,
    Instant endAt,
    String reason,
    MaintenanceState state,
    String planner,
    Instant createdAt,
    Instant cancelledAt) {

  public static MaintenanceResponse from(Maintenance m, MaintenanceState state) {
    return new MaintenanceResponse(
        m.getId(),
        m.getSite() == null ? null : m.getSite().getId(),
        m.getSite() == null ? null : m.getSite().getName(),
        m.getEquipment() == null ? null : m.getEquipment().getId(),
        m.getEquipment() == null ? null : m.getEquipment().getName(),
        m.getStartAt(),
        m.getEndAt(),
        m.getReason(),
        state,
        m.getPlanner().getUsername(),
        m.getCreatedAt(),
        m.getCancelledAt());
  }
}

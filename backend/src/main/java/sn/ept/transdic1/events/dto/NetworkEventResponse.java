package sn.ept.transdic1.events.dto;

import java.time.Instant;
import java.util.UUID;
import sn.ept.transdic1.events.domain.EventSeverity;
import sn.ept.transdic1.events.domain.EventSource;
import sn.ept.transdic1.events.domain.NetworkEvent;

/** Un événement réseau tel que l'affiche la liste « Événements ». */
public record NetworkEventResponse(
    UUID id,
    UUID equipmentId,
    String equipmentName,
    EventSource source,
    EventSeverity severity,
    String title,
    String details,
    String trapOid,
    String sourceAddress,
    String hostname,
    String appName,
    Integer facility,
    Instant receivedAt) {

  public static NetworkEventResponse from(NetworkEvent event) {
    return new NetworkEventResponse(
        event.getId(),
        event.getEquipment().getId(),
        event.getEquipment().getName(),
        event.getSource(),
        event.getSeverity(),
        event.getTitle(),
        event.getDetails(),
        event.getTrapOid(),
        event.getSourceAddress(),
        event.getHostname(),
        event.getAppName(),
        event.getFacility() == null ? null : event.getFacility().intValue(),
        event.getReceivedAt());
  }
}

package sn.ept.transdic1.alerting.dto;

import java.time.Instant;
import sn.ept.transdic1.alerting.domain.AlertEvent;
import sn.ept.transdic1.alerting.domain.AlertEventType;
import sn.ept.transdic1.alerting.domain.AlertState;

/** Une ligne de l'historique d'une alerte ; l'auteur est vide pour les événements automatiques. */
public record AlertEventResponse(
    AlertEventType type,
    AlertState previousState,
    AlertState newState,
    String author,
    String comment,
    Instant occurredAt) {

  public static AlertEventResponse from(AlertEvent event) {
    return new AlertEventResponse(
        event.getType(),
        event.getPreviousState(),
        event.getNewState(),
        event.getAuthor() == null ? null : event.getAuthor().getUsername(),
        event.getComment(),
        event.getOccurredAt());
  }
}

package sn.ept.transdic1.alerting.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.alerting.domain.Alert;
import sn.ept.transdic1.alerting.domain.AlertActivityEvent;
import sn.ept.transdic1.alerting.domain.AlertChangedEvent;
import sn.ept.transdic1.alerting.domain.AlertEvent;
import sn.ept.transdic1.alerting.domain.AlertEventType;
import sn.ept.transdic1.alerting.domain.AlertState;
import sn.ept.transdic1.alerting.domain.AlertType;
import sn.ept.transdic1.alerting.domain.MaintenanceState;
import sn.ept.transdic1.alerting.domain.ResolutionType;
import sn.ept.transdic1.alerting.domain.Severity;
import sn.ept.transdic1.alerting.repository.AlertEventRepository;
import sn.ept.transdic1.alerting.repository.AlertRepository;
import sn.ept.transdic1.alerting.repository.MaintenanceRepository;
import sn.ept.transdic1.inventory.domain.Equipment;

/**
 * Alertes EVENT ouvertes par les règles d'événements réseau (ADR 0021).
 *
 * <ul>
 *   <li>Déduplication : une seule alerte active par (équipement, règle d'événement), garantie aussi
 *       par l'index unique partiel de V30 ; les répétitions sont absorbées.
 *   <li>Rechute avant archivage : l'alerte résolue est rouverte. Si sa dernière ouverture date de
 *       moins que la fenêtre de déduplication de la règle (interface qui bagote), elle est rouverte
 *       sans nouvelle notification.
 *   <li>Maintenance : l'alerte est tracée mais personne n'est notifié.
 *   <li>Résolution automatique par un événement de retour (linkUp après linkDown).
 * </ul>
 */
@Service
public class EventAlertService {
  private static final Set<AlertState> ACTIVE = Set.of(AlertState.OPEN, AlertState.IN_PROGRESS);
  private static final Set<MaintenanceState> MAINTENANCE_STATES =
      Set.of(MaintenanceState.PLANNED, MaintenanceState.IN_PROGRESS);

  private final AlertRepository alerts;
  private final AlertEventRepository events;
  private final MaintenanceRepository maintenances;
  private final ApplicationEventPublisher publisher;

  public EventAlertService(
      AlertRepository alerts,
      AlertEventRepository events,
      MaintenanceRepository maintenances,
      ApplicationEventPublisher publisher) {
    this.alerts = alerts;
    this.events = events;
    this.maintenances = maintenances;
    this.publisher = publisher;
  }

  /** Ouvre (ou rouvre) l'alerte de la règle ; vide si une alerte est déjà active. */
  @Transactional
  public Optional<AlertChange> raise(
      Equipment equipment,
      UUID eventRuleId,
      Severity severity,
      String message,
      Duration dedupWindow,
      Instant at) {
    if (alerts
        .findFirstByEquipmentIdAndEventRuleIdAndStateIn(equipment.getId(), eventRuleId, ACTIVE)
        .isPresent()) {
      return Optional.empty();
    }
    boolean notify =
        maintenances
            .findCovering(equipment.getId(), equipment.getSite().getId(), at, MAINTENANCE_STATES)
            .isEmpty();
    var resolved =
        alerts.findFirstByEquipmentIdAndEventRuleIdAndStateOrderByResolvedAtDesc(
            equipment.getId(), eventRuleId, AlertState.RESOLVED);
    Alert alert;
    AlertEvent event;
    String comment = notify ? null : "Maintenance en cours : aucune notification";
    if (resolved.isPresent()) {
      alert = resolved.get();
      boolean flapping =
          events
              .findFirstByAlertIdAndTypeOrderByOccurredAtDesc(alert.getId(), AlertEventType.OPENED)
              .map(last -> Duration.between(last.getOccurredAt(), at).compareTo(dedupWindow) < 0)
              .orElse(false);
      alert.setState(AlertState.OPEN);
      alert.setResolvedAt(null);
      alert.setResolutionType(null);
      alert.setMessage(message);
      alert.setSeverity(severity);
      event = new AlertEvent(alert, AlertEventType.OPENED, at);
      event.setPreviousState(AlertState.RESOLVED);
      if (flapping && notify) {
        notify = false;
        comment = "Rechute dans la fenêtre de déduplication : alerte rouverte sans notification";
      } else if (notify) {
        comment = "Nouvel événement avant archivage : alerte rouverte";
      }
    } else {
      alert =
          new Alert(
              equipment,
              "event:" + eventRuleId + ":" + equipment.getId() + ":" + at.toEpochMilli(),
              AlertType.EVENT,
              AlertState.OPEN,
              severity,
              message,
              at);
      alert.setEventRuleId(eventRuleId);
      event = new AlertEvent(alert, AlertEventType.OPENED, at);
    }
    event.setNewState(AlertState.OPEN);
    event.setComment(comment);
    alerts.save(alert);
    events.save(event);
    if (notify) {
      publisher.publishEvent(new AlertChangedEvent(alert.getId(), AlertEventType.OPENED));
    }
    publisher.publishEvent(new AlertActivityEvent(alert.getId(), AlertEventType.OPENED));
    return Optional.of(new AlertChange(alert.getId(), AlertEventType.OPENED, notify));
  }

  /** Résout l'alerte active de la règle sur l'équipement ; vide s'il n'y en a pas. */
  @Transactional
  public Optional<AlertChange> clear(
      Equipment equipment, UUID eventRuleId, String comment, Instant at) {
    return alerts
        .findFirstByEquipmentIdAndEventRuleIdAndStateIn(equipment.getId(), eventRuleId, ACTIVE)
        .map(
            alert -> {
              AlertState previous = alert.getState();
              alert.setState(AlertState.RESOLVED);
              alert.setResolvedAt(at);
              alert.setResolutionType(ResolutionType.AUTOMATIC);
              var event = new AlertEvent(alert, AlertEventType.RESOLVED, at);
              event.setPreviousState(previous);
              event.setNewState(AlertState.RESOLVED);
              event.setComment(comment);
              alerts.save(alert);
              events.save(event);
              publisher.publishEvent(new AlertChangedEvent(alert.getId(), AlertEventType.RESOLVED));
              publisher.publishEvent(
                  new AlertActivityEvent(alert.getId(), AlertEventType.RESOLVED));
              return new AlertChange(alert.getId(), AlertEventType.RESOLVED, true);
            });
  }
}

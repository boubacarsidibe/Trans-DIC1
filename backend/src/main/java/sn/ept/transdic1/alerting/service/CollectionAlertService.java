package sn.ept.transdic1.alerting.service;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.alerting.domain.Alert;
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
 * Alertes techniques de collecte : l'équipement ou son agent ne répond plus. Elles n'ont pas de
 * règle, une seule peut être active par équipement, et se résolvent seules dès qu'une mesure arrive
 * de nouveau.
 */
@Service
public class CollectionAlertService {
  private static final Set<AlertState> ACTIVE = Set.of(AlertState.OPEN, AlertState.IN_PROGRESS);
  private static final Set<MaintenanceState> MAINTENANCE_STATES =
      Set.of(MaintenanceState.PLANNED, MaintenanceState.IN_PROGRESS);

  private final AlertRepository alerts;
  private final AlertEventRepository events;
  private final MaintenanceRepository maintenances;
  private final ApplicationEventPublisher publisher;

  public CollectionAlertService(
      AlertRepository alerts,
      AlertEventRepository events,
      MaintenanceRepository maintenances,
      ApplicationEventPublisher publisher) {
    this.alerts = alerts;
    this.events = events;
    this.maintenances = maintenances;
    this.publisher = publisher;
  }

  /** Ouvre l'alerte de collecte, ou la rouvre si elle vient d'être résolue. Idempotent. */
  @Transactional
  public Optional<AlertChange> reportFailure(Equipment equipment, String message, Instant at) {
    equipment.markUnreachable();
    if (alerts
        .findFirstByEquipmentIdAndTypeAndStateIn(equipment.getId(), AlertType.COLLECTION, ACTIVE)
        .isPresent()) {
      return Optional.empty();
    }
    boolean notify =
        maintenances
            .findCovering(equipment.getId(), equipment.getSite().getId(), at, MAINTENANCE_STATES)
            .isEmpty();
    var resolved =
        alerts.findFirstByEquipmentIdAndTypeAndStateOrderByResolvedAtDesc(
            equipment.getId(), AlertType.COLLECTION, AlertState.RESOLVED);
    Alert alert;
    AlertEvent event;
    if (resolved.isPresent()) {
      alert = resolved.get();
      alert.setState(AlertState.OPEN);
      alert.setResolvedAt(null);
      alert.setResolutionType(null);
      alert.setMessage(message);
      event = new AlertEvent(alert, AlertEventType.OPENED, at);
      event.setPreviousState(AlertState.RESOLVED);
      event.setComment("Nouvelle panne de collecte avant archivage : alerte rouverte");
    } else {
      alert =
          new Alert(
              equipment,
              "collection:" + equipment.getId() + ":" + at.toEpochMilli(),
              AlertType.COLLECTION,
              AlertState.OPEN,
              Severity.CRITICAL,
              message,
              at);
      event = new AlertEvent(alert, AlertEventType.OPENED, at);
    }
    event.setNewState(AlertState.OPEN);
    if (!notify) {
      event.setComment("Maintenance en cours : aucune notification");
    }
    alerts.save(alert);
    events.save(event);
    if (notify) {
      publisher.publishEvent(new AlertChangedEvent(alert.getId(), AlertEventType.OPENED));
    }
    return Optional.of(new AlertChange(alert.getId(), AlertEventType.OPENED, notify));
  }

  /**
   * Résout l'alerte de collecte active : une mesure vient d'arriver. Sans effet s'il n'y en a pas.
   */
  @Transactional
  public Optional<AlertChange> reportRecovery(Equipment equipment, Instant at) {
    return alerts
        .findFirstByEquipmentIdAndTypeAndStateIn(equipment.getId(), AlertType.COLLECTION, ACTIVE)
        .map(
            alert -> {
              AlertState previous = alert.getState();
              alert.setState(AlertState.RESOLVED);
              alert.setResolvedAt(at);
              alert.setResolutionType(ResolutionType.AUTOMATIC);
              var event = new AlertEvent(alert, AlertEventType.RESOLVED, at);
              event.setPreviousState(previous);
              event.setNewState(AlertState.RESOLVED);
              event.setComment("La collecte a repris");
              alerts.save(alert);
              events.save(event);
              publisher.publishEvent(new AlertChangedEvent(alert.getId(), AlertEventType.RESOLVED));
              return new AlertChange(alert.getId(), AlertEventType.RESOLVED, true);
            });
  }
}

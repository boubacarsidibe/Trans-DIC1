package sn.ept.transdic1.alerting.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
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
 *       par l'index unique partiel de V31 ; les répétitions sont absorbées.
 *   <li>Rechute avant archivage : l'alerte résolue est rouverte. Si sa dernière ouverture date de
 *       moins que la fenêtre de déduplication de la règle (interface qui bagote), elle est rouverte
 *       sans nouvelle notification.
 *   <li>Maintenance : l'alerte est tracée mais personne n'est notifié.
 *   <li>Résolution automatique par un événement de retour (linkUp après linkDown). Si l'ouverture
 *       n'a pas été notifiée (maintenance, rechute), la résolution ne l'est pas non plus.
 *   <li>Concurrence : ouverture et résolution prennent un verrou consultatif transactionnel
 *       PostgreSQL sur (équipement, règle). Avec les files par source (EventIntake), aucune course
 *       n'a lieu dans une instance ; le verrou couvre plusieurs instances du backend. Une violation
 *       de l'index unique ne peut pas être absorbée ici (PostgreSQL annule toute la transaction) :
 *       le travailleur rejoue alors l'événement une fois, et l'alerte est trouvée active.
 * </ul>
 */
@Service
public class EventAlertService {
  private static final Set<AlertState> ACTIVE = Set.of(AlertState.OPEN, AlertState.IN_PROGRESS);
  private static final Set<MaintenanceState> MAINTENANCE_STATES =
      Set.of(MaintenanceState.PLANNED, MaintenanceState.IN_PROGRESS);

  /** Commentaires des ouvertures non notifiées : leur résolution n'est pas notifiée non plus. */
  static final String SILENT_MAINTENANCE = "Maintenance en cours : aucune notification";

  static final String SILENT_FLAPPING =
      "Rechute dans la fenêtre de déduplication : alerte rouverte sans notification";

  private final AlertRepository alerts;
  private final AlertEventRepository events;
  private final MaintenanceRepository maintenances;
  private final ApplicationEventPublisher publisher;
  private final JdbcTemplate jdbc;

  public EventAlertService(
      AlertRepository alerts,
      AlertEventRepository events,
      MaintenanceRepository maintenances,
      ApplicationEventPublisher publisher,
      JdbcTemplate jdbc) {
    this.alerts = alerts;
    this.events = events;
    this.maintenances = maintenances;
    this.publisher = publisher;
    this.jdbc = jdbc;
  }

  /** Verrou transactionnel sur (équipement, règle), libéré à la fin de la transaction. */
  private void lock(UUID equipmentId, UUID eventRuleId) {
    jdbc.query(
        "SELECT pg_advisory_xact_lock(?, ?)",
        (RowCallbackHandler) rs -> {},
        equipmentId.hashCode(),
        eventRuleId.hashCode());
  }

  /**
   * Ouvre (ou rouvre) l'alerte de la règle ; vide si une alerte est déjà active. {@code siteId} est
   * le site de l'équipement (lu dans le cache des sources : l'équipement peut être une simple
   * référence).
   */
  @Transactional
  public Optional<AlertChange> raise(
      Equipment equipment,
      UUID siteId,
      UUID eventRuleId,
      Severity severity,
      String message,
      Duration dedupWindow,
      Instant at) {
    lock(equipment.getId(), eventRuleId);
    if (alerts
        .findFirstByEquipmentIdAndEventRuleIdAndStateIn(equipment.getId(), eventRuleId, ACTIVE)
        .isPresent()) {
      return Optional.empty();
    }
    boolean notify =
        maintenances.findCovering(equipment.getId(), siteId, at, MAINTENANCE_STATES).isEmpty();
    var resolved =
        alerts.findFirstByEquipmentIdAndEventRuleIdAndStateOrderByResolvedAtDesc(
            equipment.getId(), eventRuleId, AlertState.RESOLVED);
    Alert alert;
    AlertEvent event;
    String comment = notify ? null : SILENT_MAINTENANCE;
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
        comment = SILENT_FLAPPING;
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
    lock(equipment.getId(), eventRuleId);
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
              boolean notify = openingWasNotified(alert.getId());
              alerts.save(alert);
              events.save(event);
              if (notify) {
                publisher.publishEvent(
                    new AlertChangedEvent(alert.getId(), AlertEventType.RESOLVED));
              }
              publisher.publishEvent(
                  new AlertActivityEvent(alert.getId(), AlertEventType.RESOLVED));
              return new AlertChange(alert.getId(), AlertEventType.RESOLVED, notify);
            });
  }

  /** Faux si la dernière ouverture a été tracée sans notification (maintenance, rechute). */
  private boolean openingWasNotified(UUID alertId) {
    return events
        .findFirstByAlertIdAndTypeOrderByOccurredAtDesc(alertId, AlertEventType.OPENED)
        .map(
            opened ->
                !SILENT_MAINTENANCE.equals(opened.getComment())
                    && !SILENT_FLAPPING.equals(opened.getComment()))
        .orElse(true);
  }
}

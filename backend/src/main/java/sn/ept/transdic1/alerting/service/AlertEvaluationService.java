package sn.ept.transdic1.alerting.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
import sn.ept.transdic1.alerting.domain.AlertRule;
import sn.ept.transdic1.alerting.domain.AlertState;
import sn.ept.transdic1.alerting.domain.AlertType;
import sn.ept.transdic1.alerting.domain.MaintenanceState;
import sn.ept.transdic1.alerting.domain.ResolutionType;
import sn.ept.transdic1.alerting.repository.AlertEventRepository;
import sn.ept.transdic1.alerting.repository.AlertRepository;
import sn.ept.transdic1.alerting.repository.AlertRuleRepository;
import sn.ept.transdic1.alerting.repository.MaintenanceRepository;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.MonitoredService;

/**
 * Évalue une mesure contre les règles d'alerte qui s'appliquent à l'équipement.
 *
 * <ul>
 *   <li>Ouverture : la valeur franchit le seuil et le dépassement dure au moins la durée de
 *       confirmation de la règle.
 *   <li>Déduplication : une seule alerte active par équipement, règle et service (service nul pour
 *       les mesures sans service), garantie aussi par deux index uniques partiels (V28).
 *   <li>Résolution automatique : la valeur revient assez loin du seuil (hystérésis).
 *   <li>Nouvelle anomalie avant archivage : l'alerte résolue est rouverte.
 * </ul>
 */
@Service
public class AlertEvaluationService {
  private static final Set<AlertState> ACTIVE = Set.of(AlertState.OPEN, AlertState.IN_PROGRESS);
  private static final Set<MaintenanceState> MAINTENANCE_STATES =
      Set.of(MaintenanceState.PLANNED, MaintenanceState.IN_PROGRESS);

  private final AlertRuleRepository rules;
  private final AlertRepository alerts;
  private final AlertEventRepository events;
  private final MaintenanceRepository maintenances;
  private final MetricHistory history;
  private final ApplicationEventPublisher publisher;

  public AlertEvaluationService(
      AlertRuleRepository rules,
      AlertRepository alerts,
      AlertEventRepository events,
      MaintenanceRepository maintenances,
      MetricHistory history,
      ApplicationEventPublisher publisher) {
    this.rules = rules;
    this.alerts = alerts;
    this.events = events;
    this.maintenances = maintenances;
    this.history = history;
    this.publisher = publisher;
  }

  /** Mesure sans service (agents, SNMP) : une alerte par équipement et règle. */
  @Transactional
  public List<AlertChange> evaluate(
      Equipment equipment, String metricKey, BigDecimal value, Instant at) {
    return evaluate(equipment, null, metricKey, value, at);
  }

  /**
   * Mesure d'un service (sondes) : une alerte par équipement, règle et service. {@code service} nul
   * revient au comportement par équipement.
   */
  @Transactional
  public List<AlertChange> evaluate(
      Equipment equipment,
      MonitoredService service,
      String metricKey,
      BigDecimal value,
      Instant at) {
    var changes = new ArrayList<AlertChange>();
    UUID serviceId = service == null ? null : service.getId();
    var applicable =
        rules.findApplicable(equipment.getId(), equipment.getSite().getId(), metricKey);
    for (AlertRule rule : applicable) {
      var active = alerts.findActiveForRule(equipment.getId(), rule.getId(), serviceId, ACTIVE);
      if (active.isPresent()) {
        if (rule.isRecoveredBy(value)) {
          changes.add(resolve(active.get(), at, "Retour à la normale détecté"));
        }
      } else if (rule.isBreachedBy(value)
          && breachConfirmed(rule, equipment, serviceId, metricKey, at)) {
        changes.add(open(rule, equipment, service, metricKey, value, at));
      }
    }
    return changes;
  }

  private boolean breachConfirmed(
      AlertRule rule, Equipment equipment, UUID serviceId, String metricKey, Instant at) {
    Duration confirmation = rule.getConfirmationDuration();
    if (confirmation.isZero()) {
      return true;
    }
    // On remonte les mesures de la plus récente à la plus ancienne jusqu'à la dernière mesure
    // normale : le dépassement continu commence à la plus ancienne mesure en dépassement vue.
    Instant runStart = at;
    var past =
        history.samples(
            equipment.getId(), serviceId, metricKey, at.minus(confirmation.multipliedBy(2)), at);
    for (var sample : past) {
      if (!sample.at().isBefore(at)) {
        continue;
      }
      if (!rule.isBreachedBy(sample.value())) {
        break;
      }
      runStart = sample.at();
    }
    return !Duration.between(runStart, at).minus(confirmation).isNegative();
  }

  private AlertChange open(
      AlertRule rule,
      Equipment equipment,
      MonitoredService service,
      String metricKey,
      BigDecimal value,
      Instant at) {
    boolean notify = !underMaintenance(equipment, at);
    String message = message(rule, metricKey, value);
    if (service != null) {
      message = message + " [" + service.getName() + "]";
    }
    UUID serviceId = service == null ? null : service.getId();
    var resolved = alerts.findLastResolvedForRule(equipment.getId(), rule.getId(), serviceId);
    Alert alert;
    AlertEvent event;
    if (resolved.isPresent()) {
      alert = resolved.get();
      alert.setState(AlertState.OPEN);
      alert.setResolvedAt(null);
      alert.setResolutionType(null);
      alert.setMessage(message);
      alert.setSeverity(rule.getInitialSeverity());
      event = new AlertEvent(alert, AlertEventType.OPENED, at);
      event.setPreviousState(AlertState.RESOLVED);
      event.setComment("Nouvelle anomalie avant archivage : alerte rouverte");
    } else {
      alert =
          new Alert(
              equipment,
              rule.getId()
                  + ":"
                  + equipment.getId()
                  + (serviceId == null ? "" : ":" + serviceId)
                  + ":"
                  + at.toEpochMilli(),
              AlertType.THRESHOLD,
              AlertState.OPEN,
              rule.getInitialSeverity(),
              message,
              at);
      alert.setRule(rule);
      alert.setService(service);
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
    publisher.publishEvent(new AlertActivityEvent(alert.getId(), AlertEventType.OPENED));
    return new AlertChange(alert.getId(), AlertEventType.OPENED, notify);
  }

  /**
   * Résout automatiquement les alertes actives d'un service qui n'est plus surveillé (sonde
   * désactivée) : sinon elles resteraient ouvertes, faute de mesure pour les clore.
   */
  @Transactional
  public List<AlertChange> resolveForService(UUID serviceId, Instant at, String comment) {
    var changes = new ArrayList<AlertChange>();
    for (Alert alert : alerts.findByServiceIdAndStateIn(serviceId, ACTIVE)) {
      changes.add(resolve(alert, at, comment));
    }
    return changes;
  }

  private AlertChange resolve(Alert alert, Instant at, String comment) {
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
    publisher.publishEvent(new AlertActivityEvent(alert.getId(), AlertEventType.RESOLVED));
    return new AlertChange(alert.getId(), AlertEventType.RESOLVED, true);
  }

  private boolean underMaintenance(Equipment equipment, Instant at) {
    return !maintenances
        .findCovering(equipment.getId(), equipment.getSite().getId(), at, MAINTENANCE_STATES)
        .isEmpty();
  }

  private static String message(AlertRule rule, String metricKey, BigDecimal value) {
    String unit = rule.getUnit() == null ? "" : " " + rule.getUnit();
    return rule.getName()
        + " : "
        + metricKey
        + " = "
        + value.stripTrailingZeros().toPlainString()
        + unit
        + " (seuil "
        + rule.getOperator()
        + " "
        + rule.getThreshold().stripTrailingZeros().toPlainString()
        + ")";
  }
}

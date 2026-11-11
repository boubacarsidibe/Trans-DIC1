package sn.ept.transdic1.alerting.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
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
import sn.ept.transdic1.common.metric.MetricKey;
import sn.ept.transdic1.inventory.domain.AgentSnapshot;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.MonitoredService;
import sn.ept.transdic1.inventory.repository.AgentSnapshotRepository;

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
  private static final Duration CONTEXT_MAX_AGE = Duration.ofMinutes(10);
  private final AgentSnapshotRepository snapshots;
  private final int flappingThreshold;
  private final Duration flappingWindow;

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
      ApplicationEventPublisher publisher,
      AgentSnapshotRepository snapshots,
      @Value("${alerting.flapping.threshold:4}") int flappingThreshold,
      @Value("${alerting.flapping.window-minutes:60}") long flappingWindowMinutes) {
    this.rules = rules;
    this.alerts = alerts;
    this.events = events;
    this.maintenances = maintenances;
    this.history = history;
    this.publisher = publisher;
    this.snapshots = snapshots;
    // Au moins deux ouvertures : en dessous, toute réouverture serait « instable ».
    this.flappingThreshold = Math.max(2, flappingThreshold);
    this.flappingWindow = Duration.ofMinutes(Math.max(5, flappingWindowMinutes));
  }

  /** Ouvertures déjà enregistrées pour cette alerte dans la fenêtre d'instabilité. */
  private int openingsInWindow(java.util.UUID alertId, Instant at) {
    return events.countByAlertIdAndTypeAndOccurredAtGreaterThanEqual(
        alertId, AlertEventType.OPENED, at.minus(flappingWindow));
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
    String instance = MetricKey.instance(metricKey);
    var applicable =
        preferExact(
            rules.findApplicable(
                equipment.getId(), equipment.getSite().getId(), MetricKey.ruleTypes(metricKey)),
            metricKey);
    for (AlertRule rule : applicable) {
      var active =
          alerts.findActiveForRule(equipment.getId(), rule.getId(), serviceId, instance, ACTIVE);
      if (active.isPresent()) {
        if (rule.isRecoveredBy(value)) {
          changes.add(resolve(active.get(), at, "Retour à la normale détecté"));
        }
      } else if (rule.isBreachedBy(value)
          && breachConfirmed(rule, equipment, service, metricKey, at)) {
        changes.add(open(rule, equipment, service, metricKey, value, at));
      }
    }
    return changes;
  }

  /** Une règle posée sur un disque précis remplace la règle générique pour ce disque. */
  private static List<AlertRule> preferExact(List<AlertRule> found, String metricKey) {
    var exact = found.stream().filter(r -> metricKey.equals(r.getMetricType())).toList();
    return exact.isEmpty() ? found : exact;
  }

  /** Dernier instantané reçu depuis moins de 10 minutes, pour une alerte CPU ou mémoire. */
  private String contextFor(Equipment equipment, String metricKey, Instant at) {
    if (!metricKey.startsWith("cpu.") && !metricKey.startsWith("memory.")) {
      return null;
    }
    return snapshots
        .findById(equipment.getId())
        .filter(s -> !s.getReceivedAt().isBefore(at.minus(CONTEXT_MAX_AGE)))
        .map(AgentSnapshot::getPayload)
        .orElse(null);
  }

  private boolean breachConfirmed(
      AlertRule rule, Equipment equipment, MonitoredService service, String metricKey, Instant at) {
    Duration confirmation = rule.getConfirmationDuration();
    if (confirmation.isZero()) {
      return true;
    }
    // On remonte les mesures de la plus récente à la plus ancienne jusqu'à la dernière mesure
    // normale : le dépassement continu commence à la plus ancienne mesure en dépassement vue.
    Instant runStart = at;
    var past =
        history.samples(
            equipment.getId(),
            service == null ? null : service.getId(),
            metricKey,
            at.minus(lookBack(confirmation, service)),
            at);
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

  /**
   * Fenêtre d'historique pour confirmer un dépassement : deux fois la durée de confirmation, et
   * pour une sonde au moins deux intervalles plus une minute, sinon une sonde lente (jusqu'à 24 h
   * entre deux exécutions) ne verrait jamais sa mesure précédente et n'alerterait jamais.
   */
  static Duration lookBack(Duration confirmation, MonitoredService service) {
    Duration window = confirmation.multipliedBy(2);
    if (service == null) {
      return window;
    }
    Duration probe = Duration.ofSeconds(2L * service.getIntervalSeconds() + 60);
    return probe.compareTo(window) > 0 ? probe : window;
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
    String instance = MetricKey.instance(metricKey);
    var resolved =
        alerts.findLastResolvedForRule(equipment.getId(), rule.getId(), serviceId, instance);
    Alert alert;
    AlertEvent event;
    String flappingNote = null;
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
      // Celle-ci comprise : l'événement n'est pas encore enregistré.
      int openings = openingsInWindow(alert.getId(), at) + 1;
      if (FlappingDetector.isFlapping(openings, flappingThreshold)) {
        if (alert.getFlappingSince() == null) {
          alert.setFlappingSince(at);
        }
        flappingNote = FlappingDetector.explain(openings, (int) flappingWindow.toMinutes());
      } else if (alert.getFlappingSince() != null
          && FlappingDetector.isCalmAgain(openings, flappingThreshold)) {
        alert.setFlappingSince(null);
      }
    } else {
      alert =
          new Alert(
              equipment,
              rule.getId()
                  + ":"
                  + equipment.getId()
                  + (serviceId == null ? "" : ":" + serviceId)
                  + (instance == null ? "" : ":" + instance)
                  + ":"
                  + at.toEpochMilli(),
              AlertType.THRESHOLD,
              AlertState.OPEN,
              rule.getInitialSeverity(),
              message,
              at);
      alert.setRule(rule);
      alert.setService(service);
      alert.setMetricInstance(instance);
      event = new AlertEvent(alert, AlertEventType.OPENED, at);
    }
    event.setNewState(AlertState.OPEN);
    alert.setContext(contextFor(equipment, metricKey, at));
    if (!notify) {
      event.setComment("Maintenance en cours : aucune notification");
    } else if (alert.getFlappingSince() != null) {
      // L'alerte reste visible et vit sa vie, mais elle ne réveille plus personne.
      notify = false;
      event.setComment(
          flappingNote == null
              ? "Alerte instable : notifications toujours suspendues"
              : flappingNote);
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

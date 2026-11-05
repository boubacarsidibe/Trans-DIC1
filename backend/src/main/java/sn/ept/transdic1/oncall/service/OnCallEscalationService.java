package sn.ept.transdic1.oncall.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.alerting.domain.Alert;
import sn.ept.transdic1.alerting.domain.AlertActivityEvent;
import sn.ept.transdic1.alerting.domain.AlertChangedEvent;
import sn.ept.transdic1.alerting.domain.AlertEvent;
import sn.ept.transdic1.alerting.domain.AlertEventType;
import sn.ept.transdic1.alerting.domain.AlertState;
import sn.ept.transdic1.alerting.domain.MaintenanceState;
import sn.ept.transdic1.alerting.domain.Severity;
import sn.ept.transdic1.alerting.repository.AlertEventRepository;
import sn.ept.transdic1.alerting.repository.AlertRepository;
import sn.ept.transdic1.alerting.repository.MaintenanceRepository;
import sn.ept.transdic1.audit.service.AuditService;
import sn.ept.transdic1.common.scheduling.JobGuard;
import sn.ept.transdic1.oncall.domain.OnCallAlertStep;
import sn.ept.transdic1.oncall.domain.OnCallLevel;
import sn.ept.transdic1.oncall.domain.OnCallOutcome;
import sn.ept.transdic1.oncall.repository.OnCallAlertStepRepository;
import sn.ept.transdic1.oncall.service.OnCallResolver.OnCallPerson;
import sn.ept.transdic1.reporting.service.NotificationQueueService;
import sn.ept.transdic1.reporting.service.OnCallDelivery;

/**
 * Chaîne d'escalade d'astreinte (ADR 0025), placée avant l'escalade aux administrateurs (qui reste
 * le dernier niveau, inchangée) :
 *
 * <ol>
 *   <li>à l'ouverture d'une alerte (hors maintenance, sévérité suffisante, site couvert par un
 *       planning actif), la personne d'astreinte principale est prévenue tout de suite ;
 *   <li>si l'alerte est toujours ouverte après le délai (plus court si critique), la personne
 *       d'astreinte secondaire est prévenue ; si le principal n'a pas pu être prévenu (personne,
 *       aucun canal, hors périmètre), le secondaire l'est sans attendre.
 * </ol>
 *
 * <p>Prise en charge ou résolution arrêtent la chaîne. Chaque niveau est franchi une seule fois par
 * alerte (étape persistée, contrainte unique) et tracé dans la chronologie de l'alerte.
 */
@Service
public class OnCallEscalationService {
  private static final Logger LOG = LoggerFactory.getLogger(OnCallEscalationService.class);
  private static final Set<MaintenanceState> MAINTENANCE_STATES =
      Set.of(MaintenanceState.PLANNED, MaintenanceState.IN_PROGRESS);

  private final AlertRepository alerts;
  private final AlertEventRepository events;
  private final MaintenanceRepository maintenances;
  private final OnCallResolver resolver;
  private final OnCallAlertStepRepository steps;
  private final NotificationQueueService queue;
  private final ApplicationEventPublisher publisher;
  private final AuditService audit;
  private final JobGuard guard;
  private final Clock clock;
  private final Severity minimumSeverity;
  private final Duration secondaryAfter;
  private final Duration criticalSecondaryAfter;

  public OnCallEscalationService(
      AlertRepository alerts,
      AlertEventRepository events,
      MaintenanceRepository maintenances,
      OnCallResolver resolver,
      OnCallAlertStepRepository steps,
      NotificationQueueService queue,
      ApplicationEventPublisher publisher,
      AuditService audit,
      JobGuard guard,
      Clock clock,
      @Value("${oncall.notify-min-severity:WARNING}") Severity minimumSeverity,
      @Value("${oncall.secondary-after-minutes:10}") long secondaryAfterMinutes,
      @Value("${oncall.critical-secondary-after-minutes:5}") long criticalSecondaryAfterMinutes) {
    this.alerts = alerts;
    this.events = events;
    this.maintenances = maintenances;
    this.resolver = resolver;
    this.steps = steps;
    this.queue = queue;
    this.publisher = publisher;
    this.audit = audit;
    this.guard = guard;
    this.clock = clock;
    this.minimumSeverity = minimumSeverity;
    this.secondaryAfter = Duration.ofMinutes(Math.max(1, secondaryAfterMinutes));
    this.criticalSecondaryAfter = Duration.ofMinutes(Math.max(1, criticalSecondaryAfterMinutes));
  }

  /** Dans la transaction de l'alerte, comme la file des notifications (pattern Outbox). */
  @EventListener
  @Transactional
  public void onAlertChanged(AlertChangedEvent event) {
    if (event.type() == AlertEventType.OPENED) {
      startChain(event.alertId(), clock.instant());
    }
  }

  /** Premier niveau ; retourne vrai si la chaîne a démarré pour cette alerte. */
  @Transactional
  public boolean startChain(UUID alertId, Instant now) {
    Alert alert = alerts.findById(alertId).orElse(null);
    if (alert == null
        || alert.getState() != AlertState.OPEN
        || alert.getSeverity().ordinal() < minimumSeverity.ordinal()
        || steps.existsByAlertIdAndLevel(alertId, OnCallLevel.PRIMARY)
        || underMaintenance(alert, now)) {
      return false;
    }
    var resolution = resolver.resolve(siteOf(alert), now);
    if (!resolution.covered()) {
      // Aucun planning pour ce site : comportement d'avant l'astreinte, rien n'est tracé.
      return false;
    }
    OnCallOutcome outcome = notify(alert, OnCallLevel.PRIMARY, resolution.primary(), now);
    if (outcome != OnCallOutcome.NOTIFIED) {
      // Le principal n'a pas pu être prévenu : la chaîne passe au secondaire sans attendre.
      notify(alert, OnCallLevel.SECONDARY, resolution.secondary(), now);
    }
    return true;
  }

  @Scheduled(cron = "${oncall.escalation.cron:20 * * * * *}", zone = "UTC")
  public void scheduledCheck() {
    guard.run("oncall.escalation", Duration.ofMinutes(5), () -> escalateSecondary(clock.instant()));
  }

  /** Second niveau pour les alertes dues ; retourne le nombre d'alertes traitées. */
  @Transactional
  public int escalateSecondary(Instant now) {
    int done = 0;
    for (OnCallAlertStep primary : steps.findAwaitingSecondary(AlertState.OPEN)) {
      Alert alert = alerts.findById(primary.getAlertId()).orElse(null);
      if (alert == null || alert.getState() != AlertState.OPEN) {
        continue;
      }
      Duration limit =
          alert.getSeverity() == Severity.CRITICAL ? criticalSecondaryAfter : secondaryAfter;
      if (now.isBefore(alert.getTriggeredAt().plus(limit))) {
        continue;
      }
      // Pendant une maintenance, rien n'est envoyé ; l'étape reste due pour après.
      if (underMaintenance(alert, now)) {
        continue;
      }
      notify(
          alert,
          OnCallLevel.SECONDARY,
          resolver.resolve(siteOf(alert), now, OnCallLevel.SECONDARY),
          now);
      done++;
    }
    if (done > 0) {
      LOG.info("Astreinte : {} alerte(s) passée(s) au niveau secondaire", done);
    }
    return done;
  }

  /** Franchit un niveau : message en file, étape persistée, événement dans la chronologie. */
  private OnCallOutcome notify(
      Alert alert, OnCallLevel level, Optional<OnCallPerson> person, Instant now) {
    if (steps.existsByAlertIdAndLevel(alert.getId(), level)) {
      return steps.findByAlertIdOrderByOccurredAtAsc(alert.getId()).stream()
          .filter(s -> s.getLevel() == level)
          .findFirst()
          .map(OnCallAlertStep::getOutcome)
          .orElse(OnCallOutcome.NOTIFIED);
    }
    String levelLabel = level == OnCallLevel.PRIMARY ? "principal" : "secondaire";
    OnCallOutcome outcome;
    String comment;
    if (person.isEmpty()) {
      outcome = OnCallOutcome.NOBODY;
      comment = "Astreinte : personne d'astreinte au niveau " + levelLabel;
    } else {
      String name = person.get().username();
      OnCallDelivery delivery =
          queue.queueOnCall(alert.getId(), person.get().userId(), level == OnCallLevel.SECONDARY);
      switch (delivery) {
        case QUEUED -> {
          outcome = OnCallOutcome.NOTIFIED;
          comment = "Astreinte : " + name + " prévenu(e) (niveau " + levelLabel + ")";
        }
        case NO_CHANNEL -> {
          outcome = OnCallOutcome.NO_CHANNEL;
          comment =
              "Astreinte sans canal : "
                  + name
                  + " n'a aucun canal de notification actif (niveau "
                  + levelLabel
                  + ") ; la chaîne continue";
        }
        default -> {
          outcome = OnCallOutcome.OUT_OF_SCOPE;
          comment =
              "Astreinte : "
                  + name
                  + " ne voit pas le site de cette alerte (niveau "
                  + levelLabel
                  + ") ; rien n'est envoyé";
        }
      }
    }
    steps.save(
        new OnCallAlertStep(
            alert.getId(), level, person.map(OnCallPerson::userId).orElse(null), outcome, now));
    var event = new AlertEvent(alert, AlertEventType.NOTIFIED, now);
    event.setComment(comment);
    events.save(event);
    publisher.publishEvent(new AlertActivityEvent(alert.getId(), AlertEventType.NOTIFIED));
    audit.record(
        "ONCALL_" + level.name(),
        "ALERT",
        alert.getId(),
        "resultat=" + outcome + person.map(p -> " personne=" + p.username()).orElse(""));
    return outcome;
  }

  private boolean underMaintenance(Alert alert, Instant at) {
    UUID site = siteOf(alert);
    return !maintenances
        .findCovering(alert.getEquipment().getId(), site, at, MAINTENANCE_STATES)
        .isEmpty();
  }

  private static UUID siteOf(Alert alert) {
    var site = alert.getEquipment().getSite();
    return site == null ? null : site.getId();
  }
}

package sn.ept.transdic1.alerting.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.alerting.domain.Alert;
import sn.ept.transdic1.alerting.domain.AlertActivityEvent;
import sn.ept.transdic1.alerting.domain.AlertChangedEvent;
import sn.ept.transdic1.alerting.domain.AlertEvent;
import sn.ept.transdic1.alerting.domain.AlertEventType;
import sn.ept.transdic1.alerting.domain.AlertState;
import sn.ept.transdic1.alerting.domain.Severity;
import sn.ept.transdic1.alerting.repository.AlertEventRepository;
import sn.ept.transdic1.alerting.repository.AlertRepository;
import sn.ept.transdic1.audit.service.AuditService;
import sn.ept.transdic1.common.scheduling.JobGuard;

/**
 * Escalade temporisée : une alerte restée ouverte (personne ne l'a prise en charge) au-delà du
 * délai prévu est signalée aux administrateurs, une seule fois. Le délai est plus court pour une
 * alerte critique. L'escalade est tracée dans la chronologie de l'alerte et dans le journal d'audit
 * ; l'envoi du message est fait par le module de notifications, à l'écoute de l'événement.
 */
@Service
public class AlertEscalationService {
  private static final Logger LOG = LoggerFactory.getLogger(AlertEscalationService.class);

  private final AlertRepository alerts;
  private final AlertEventRepository events;
  private final ApplicationEventPublisher publisher;
  private final AuditService audit;
  private final JobGuard guard;
  private final Clock clock;
  private final Duration delay;
  private final Duration criticalDelay;

  public AlertEscalationService(
      AlertRepository alerts,
      AlertEventRepository events,
      ApplicationEventPublisher publisher,
      AuditService audit,
      JobGuard guard,
      Clock clock,
      @Value("${alerting.escalation.delay-minutes:30}") long delayMinutes,
      @Value("${alerting.escalation.critical-delay-minutes:10}") long criticalDelayMinutes) {
    this.alerts = alerts;
    this.events = events;
    this.publisher = publisher;
    this.audit = audit;
    this.guard = guard;
    this.clock = clock;
    this.delay = Duration.ofMinutes(Math.max(1, delayMinutes));
    this.criticalDelay = Duration.ofMinutes(Math.max(1, criticalDelayMinutes));
  }

  /** Passage planifié : une seule instance à la fois. */
  @Scheduled(cron = "${alerting.escalation.cron:15 * * * * *}", zone = "UTC")
  public void scheduledCheck() {
    guard.run("alerting.escalation", Duration.ofMinutes(5), this::escalateOverdue);
  }

  /** Retourne le nombre d'alertes escaladées par ce passage. */
  @Transactional
  public int escalateOverdue() {
    Instant now = clock.instant();
    int escalated = 0;
    // Le délai le plus court borne la recherche ; chaque alerte est ensuite jugée selon sa
    // sévérité.
    var candidates =
        alerts.findByStateAndEscalatedAtIsNullAndTriggeredAtBefore(
            AlertState.OPEN, now.minus(criticalDelay.compareTo(delay) < 0 ? criticalDelay : delay));
    for (Alert alert : candidates) {
      Duration limit = alert.getSeverity() == Severity.CRITICAL ? criticalDelay : delay;
      Duration waited = Duration.between(alert.getTriggeredAt(), now);
      if (waited.compareTo(limit) < 0) {
        continue;
      }
      escalate(alert, waited, now);
      escalated++;
    }
    if (escalated > 0) {
      LOG.info("Escalade : {} alerte(s) non prise(s) en charge", escalated);
    }
    return escalated;
  }

  private void escalate(Alert alert, Duration waited, Instant now) {
    alert.setEscalatedAt(now);
    alerts.save(alert);
    var event = new AlertEvent(alert, AlertEventType.ESCALATED, now);
    event.setComment(
        "Non prise en charge après " + waited.toMinutes() + " min : administrateurs prévenus");
    events.save(event);
    publisher.publishEvent(new AlertChangedEvent(alert.getId(), AlertEventType.ESCALATED));
    publisher.publishEvent(new AlertActivityEvent(alert.getId(), AlertEventType.ESCALATED));
    audit.record(
        "ALERT_ESCALATED", "ALERT", alert.getId(), "attente=" + waited.toMinutes() + "min");
  }
}

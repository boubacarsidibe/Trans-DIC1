package sn.ept.transdic1.reporting.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.alerting.domain.Alert;
import sn.ept.transdic1.alerting.domain.AlertChangedEvent;
import sn.ept.transdic1.alerting.domain.AlertEventType;
import sn.ept.transdic1.alerting.domain.Severity;
import sn.ept.transdic1.alerting.repository.AlertRepository;
import sn.ept.transdic1.reporting.domain.Notification;
import sn.ept.transdic1.reporting.domain.NotificationChannel;
import sn.ept.transdic1.reporting.domain.NotificationPreference;
import sn.ept.transdic1.reporting.domain.NotificationState;
import sn.ept.transdic1.reporting.repository.NotificationPreferenceRepository;
import sn.ept.transdic1.reporting.repository.NotificationRepository;
import sn.ept.transdic1.security.domain.Role;
import sn.ept.transdic1.security.repository.UserRepository;

/**
 * Prépare les e-mails d'une alerte : un destinataire par utilisateur actif dont la préférence est
 * activée et dont le seuil de sévérité est atteint. L'envoi est fait plus tard, hors de la
 * transaction de l'alerte, par {@link NotificationDispatchService}.
 */
@Service
public class NotificationQueueService {
  private static final Logger LOG = LoggerFactory.getLogger(NotificationQueueService.class);
  private static final Map<Severity, String> SEVERITY_LABELS =
      Map.of(
          Severity.CRITICAL, "CRITIQUE", Severity.WARNING, "AVERTISSEMENT", Severity.INFO, "INFO");

  private final AlertRepository alerts;
  private final NotificationPreferenceRepository preferences;
  private final NotificationRepository notifications;
  private final UserRepository users;

  public NotificationQueueService(
      AlertRepository alerts,
      NotificationPreferenceRepository preferences,
      NotificationRepository notifications,
      UserRepository users) {
    this.users = users;
    this.alerts = alerts;
    this.preferences = preferences;
    this.notifications = notifications;
  }

  /** Dans la transaction de l'alerte : les notifications et l'alerte sont validées ensemble. */
  @EventListener
  @Transactional
  public void onAlertChanged(AlertChangedEvent event) {
    queueFor(event.alertId(), event.type());
  }

  /** Retourne le nombre de notifications préparées. */
  @Transactional
  public int queueFor(UUID alertId, AlertEventType type) {
    Alert alert = alerts.findById(alertId).orElse(null);
    if (alert == null) {
      return 0;
    }
    String subject = subject(alert, type);
    int queued = 0;
    if (type == AlertEventType.ESCALATED) {
      return queueEscalation(alert, subject);
    }
    for (NotificationPreference preference : recipients(alert.getSeverity())) {
      var user = preference.getUser();
      if (!user.isActive() || user.getEmail() == null || user.getEmail().isBlank()) {
        continue;
      }
      var notification =
          new Notification(
              user, preference.getChannel(), user.getEmail(), subject, NotificationState.PENDING);
      notification.setAlert(alert);
      notifications.save(notification);
      queued++;
    }
    if (queued > 0) {
      LOG.info("Notifications préparées pour l'alerte {} : {}", alertId, queued);
    }
    return queued;
  }

  /**
   * Une alerte non prise en charge prévient les administrateurs actifs, quelles que soient leurs
   * préférences.
   */
  private int queueEscalation(Alert alert, String subject) {
    int queued = 0;
    for (var admin : users.findByRoleAndActiveTrue(Role.ADMIN)) {
      if (admin.getEmail() == null || admin.getEmail().isBlank()) {
        continue;
      }
      var notification =
          new Notification(
              admin,
              NotificationChannel.EMAIL,
              admin.getEmail(),
              subject,
              NotificationState.PENDING);
      notification.setAlert(alert);
      notifications.save(notification);
      queued++;
    }
    return queued;
  }

  private List<NotificationPreference> recipients(Severity severity) {
    return preferences.findAll().stream()
        .filter(NotificationPreference::isActive)
        .filter(p -> p.getMinimumSeverity().ordinal() <= severity.ordinal())
        .toList();
  }

  static String subject(Alert alert, AlertEventType type) {
    if (type == AlertEventType.ESCALATED) {
      return "[ESCALADE] Alerte non prise en charge — " + alert.getEquipment().getName();
    }
    String state = type == AlertEventType.RESOLVED ? "Résolue" : "Ouverte";
    return "["
        + SEVERITY_LABELS.getOrDefault(alert.getSeverity(), alert.getSeverity().name())
        + "] Alerte "
        + state.toLowerCase()
        + " — "
        + alert.getEquipment().getName();
  }
}

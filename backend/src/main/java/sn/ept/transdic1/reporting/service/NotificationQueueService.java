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
import sn.ept.transdic1.common.scope.SiteScope;
import sn.ept.transdic1.reporting.domain.Notification;
import sn.ept.transdic1.reporting.domain.NotificationChannel;
import sn.ept.transdic1.reporting.domain.NotificationPreference;
import sn.ept.transdic1.reporting.domain.NotificationState;
import sn.ept.transdic1.reporting.repository.NotificationPreferenceRepository;
import sn.ept.transdic1.reporting.repository.NotificationRepository;
import sn.ept.transdic1.security.domain.AppUser;
import sn.ept.transdic1.security.domain.Role;
import sn.ept.transdic1.security.repository.UserRepository;

/**
 * Écrit les notifications d'une alerte dans la table d'envoi (pattern Outbox) : un destinataire par
 * utilisateur actif dont la préférence est activée et dont le seuil de sévérité est atteint, sur le
 * canal qu'il a choisi. L'écriture a lieu dans la transaction de l'alerte (les deux sont validées
 * ensemble ou pas du tout) ; l'envoi est fait plus tard par {@link NotificationDispatchService}.
 * Chaque ligne porte une clé d'idempotence (événement + destinataire + canal) : le même événement
 * rejoué ne crée jamais un second message.
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
  private final MessageTemplateService renderer;
  private final SiteScope scope;

  public NotificationQueueService(
      AlertRepository alerts,
      NotificationPreferenceRepository preferences,
      NotificationRepository notifications,
      UserRepository users,
      MessageTemplateService renderer,
      SiteScope scope) {
    this.scope = scope;
    this.alerts = alerts;
    this.preferences = preferences;
    this.notifications = notifications;
    this.users = users;
    this.renderer = renderer;
  }

  /** Dans la transaction de l'alerte : les notifications et l'alerte sont validées ensemble. */
  @EventListener
  @Transactional
  public void onAlertChanged(AlertChangedEvent event) {
    queueFor(event.eventId(), event.alertId(), event.type());
  }

  /** Retourne le nombre de notifications préparées (0 si l'événement a déjà été traité). */
  @Transactional
  public int queueFor(UUID eventId, UUID alertId, AlertEventType type) {
    Alert alert = alerts.findById(alertId).orElse(null);
    if (alert == null) {
      return 0;
    }
    String kind = kind(type);
    String subject = renderer.render(kind, alert).subject();
    int queued = 0;
    if (type == AlertEventType.ESCALATED) {
      for (AppUser admin : users.findByRoleAndActiveTrue(Role.ADMIN)) {
        if (add(
            eventId, kind, alert, subject, admin, NotificationChannel.EMAIL, admin.getEmail())) {
          queued++;
        }
      }
    } else {
      for (NotificationPreference preference : recipients(alert.getSeverity())) {
        AppUser user = preference.getUser();
        // Périmètre d'accès (ADR 0018) : personne n'est prévenu d'une alerte qu'il ne peut pas
        // voir.
        if (!user.isActive() || !scope.allowsUser(user.getId(), siteOf(alert))) {
          continue;
        }
        String destination =
            preference.getChannel() == NotificationChannel.EMAIL
                ? user.getEmail()
                : preference.getDestination();
        if (add(eventId, kind, alert, subject, user, preference.getChannel(), destination)) {
          queued++;
        }
      }
    }
    if (queued > 0) {
      LOG.info("Notifications préparées pour l'alerte {} : {}", alertId, queued);
    }
    return queued;
  }

  private boolean add(
      UUID eventId,
      String kind,
      Alert alert,
      String subject,
      AppUser user,
      NotificationChannel channel,
      String destination) {
    if (destination == null || destination.isBlank()) {
      return false;
    }
    String dedupKey = eventId + ":" + user.getId() + ":" + channel;
    if (notifications.existsByDedupKey(dedupKey)) {
      return false;
    }
    var notification =
        new Notification(user, channel, destination, subject, NotificationState.PENDING);
    notification.setAlert(alert);
    notification.setKind(kind);
    notification.setDedupKey(dedupKey);
    notifications.save(notification);
    return true;
  }

  private static UUID siteOf(Alert alert) {
    var site = alert.getEquipment().getSite();
    return site == null ? null : site.getId();
  }

  private List<NotificationPreference> recipients(Severity severity) {
    return preferences.findAll().stream()
        .filter(NotificationPreference::isActive)
        .filter(p -> p.getMinimumSeverity().ordinal() <= severity.ordinal())
        .toList();
  }

  static String kind(AlertEventType type) {
    return switch (type) {
      case RESOLVED -> "ALERT_RESOLVED";
      case ESCALATED -> "ALERT_ESCALATED";
      default -> "ALERT_OPENED";
    };
  }

  /** Libellé français de la sévérité, utilisé dans les messages. */
  static String label(Severity severity) {
    return SEVERITY_LABELS.getOrDefault(severity, severity.name());
  }
}

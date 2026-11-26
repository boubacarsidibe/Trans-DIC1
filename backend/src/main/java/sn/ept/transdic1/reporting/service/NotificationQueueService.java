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
import sn.ept.transdic1.common.alerting.IncidentGrouping;
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
  private static final String ONCALL_KIND = "ALERT_ONCALL";
  private static final Map<Severity, String> SEVERITY_LABELS =
      Map.of(
          Severity.CRITICAL, "CRITIQUE", Severity.WARNING, "AVERTISSEMENT", Severity.INFO, "INFO");

  private final AlertRepository alerts;
  private final NotificationPreferenceRepository preferences;
  private final NotificationRepository notifications;
  private final UserRepository users;
  private final MessageTemplateService renderer;
  private final SiteScope scope;
  private final IncidentGrouping incidents;

  public NotificationQueueService(
      AlertRepository alerts,
      NotificationPreferenceRepository preferences,
      NotificationRepository notifications,
      UserRepository users,
      MessageTemplateService renderer,
      SiteScope scope,
      IncidentGrouping incidents) {
    this.scope = scope;
    this.incidents = incidents;
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
    // Un fait, une notification (M10, ADR 0039) : quand une panne en amont fait tomber cinq
    // machines, une seule des cinq alertes prend la parole. Les autres restent visibles à l'écran,
    // dans le même incident. Une alerte qu'aucun incident n'a rangée notifie toujours.
    if (type == AlertEventType.OPENED && !incidents.announces(alertId)) {
      LOG.debug("Alerte {} rangée dans un incident déjà notifié : aucun message", alertId);
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

  /**
   * Message d'astreinte (ADR 0025) pour une personne, sur son propre canal (sa préférence active).
   * Le seuil de sévérité de sa préférence ne s'applique pas : être d'astreinte, c'est accepter
   * d'être prévenu ; le seuil est celui de l'astreinte ({@code oncall.notify-min-severity}). Une
   * clé d'idempotence par alerte, niveau et personne empêche tout doublon.
   */
  @Transactional
  public OnCallDelivery queueOnCall(UUID alertId, UUID userId, boolean secondary) {
    return queueToOnCall(
        alertId,
        userId,
        secondary ? "[2e niveau] " : "",
        "oncall:" + alertId + ":" + (secondary ? "SECONDARY" : "PRIMARY") + ":" + userId);
  }

  /**
   * Rappel à la personne d'astreinte hors de la chaîne d'escalade (ADR 0057) : une correction
   * automatique n'a pas suffi. Un rappel par fait (clé), jamais deux.
   */
  public OnCallDelivery queueOnCallReminder(
      UUID alertId, UUID userId, String subjectPrefix, String factKey) {
    return queueToOnCall(
        alertId,
        userId,
        subjectPrefix,
        "oncall:" + alertId + ":REMINDER:" + factKey + ":" + userId);
  }

  private OnCallDelivery queueToOnCall(
      UUID alertId, UUID userId, String subjectPrefix, String dedupKey) {
    Alert alert = alerts.findById(alertId).orElseThrow();
    AppUser user = users.findById(userId).orElseThrow();
    if (!scope.allowsUser(user.getId(), siteOf(alert))) {
      return OnCallDelivery.OUT_OF_SCOPE;
    }
    var preference = preferences.findByUserId(userId).filter(NotificationPreference::isActive);
    if (preference.isEmpty()) {
      return OnCallDelivery.NO_CHANNEL;
    }
    NotificationChannel channel = preference.get().getChannel();
    String destination =
        channel == NotificationChannel.EMAIL ? user.getEmail() : preference.get().getDestination();
    if (destination == null || destination.isBlank()) {
      return OnCallDelivery.NO_CHANNEL;
    }
    String subject = subjectPrefix + renderer.render(ONCALL_KIND, alert).subject();
    if (subject.length() > 255) {
      subject = subject.substring(0, 255);
    }
    if (!notifications.existsByDedupKey(dedupKey)) {
      var notification =
          new Notification(user, channel, destination, subject, NotificationState.PENDING);
      notification.setAlert(alert);
      notification.setKind(ONCALL_KIND);
      notification.setDedupKey(dedupKey);
      notifications.save(notification);
      LOG.info("Notification d'astreinte préparée pour l'alerte {}", alertId);
    }
    return OnCallDelivery.QUEUED;
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

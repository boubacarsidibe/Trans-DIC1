package sn.ept.transdic1.reporting.service;

import java.time.Clock;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import sn.ept.transdic1.common.secret.SecretResolver;
import sn.ept.transdic1.reporting.domain.Notification;
import sn.ept.transdic1.reporting.domain.NotificationAttempt;
import sn.ept.transdic1.reporting.domain.NotificationChannel;
import sn.ept.transdic1.reporting.domain.NotificationState;
import sn.ept.transdic1.reporting.domain.SendResult;
import sn.ept.transdic1.reporting.dto.NotificationAdminDtos.HistoryItem;
import sn.ept.transdic1.reporting.dto.NotificationAdminDtos.TemplateRequest;
import sn.ept.transdic1.reporting.dto.NotificationAdminDtos.TemplateResponse;
import sn.ept.transdic1.reporting.dto.NotificationAdminDtos.TestRequest;
import sn.ept.transdic1.reporting.dto.NotificationAdminDtos.TestResponse;
import sn.ept.transdic1.reporting.port.NotificationSender;
import sn.ept.transdic1.reporting.repository.NotificationAttemptRepository;
import sn.ept.transdic1.reporting.repository.NotificationRepository;
import sn.ept.transdic1.reporting.repository.NotificationTemplateRepository;

/** Administration des notifications : historique d'envoi, rejeu, modèles et envoi de test. */
@Service
public class NotificationAdminService {
  private final NotificationRepository notifications;
  private final NotificationAttemptRepository attempts;
  private final NotificationTemplateRepository templates;
  private final MessageTemplateService renderer;
  private final Map<NotificationChannel, NotificationSender> senders =
      new EnumMap<>(NotificationChannel.class);
  private final Clock clock;

  public NotificationAdminService(
      NotificationRepository notifications,
      NotificationAttemptRepository attempts,
      NotificationTemplateRepository templates,
      MessageTemplateService renderer,
      List<NotificationSender> senderList,
      Clock clock) {
    this.notifications = notifications;
    this.attempts = attempts;
    this.templates = templates;
    this.renderer = renderer;
    senderList.forEach(sender -> senders.put(sender.channel(), sender));
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public Page<HistoryItem> history(Pageable pageable) {
    return notifications.findAllByOrderByCreatedAtDesc(pageable).map(this::item);
  }

  /** Remet une notification abandonnée en attente : le worker tente un nouvel envoi. */
  @Transactional
  public HistoryItem replay(UUID id) {
    Notification notification =
        notifications
            .findById(id)
            .orElseThrow(
                () ->
                    new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification introuvable"));
    if (notification.getState() != NotificationState.FAILED) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Seule une notification en échec peut être rejouée");
    }
    notification.setState(NotificationState.PENDING);
    notifications.save(notification);
    return item(notification);
  }

  @Transactional(readOnly = true)
  public List<TemplateResponse> templates() {
    return templates.findAllByOrderByKindAsc().stream()
        .map(
            t ->
                new TemplateResponse(
                    t.getKind(), t.getSubjectTemplate(), t.getBodyTemplate(), t.getUpdatedAt()))
        .toList();
  }

  @Transactional
  public TemplateResponse updateTemplate(String kind, TemplateRequest request) {
    var template =
        templates
            .findById(kind)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Modèle introuvable"));
    template.update(request.subjectTemplate().trim(), request.bodyTemplate(), clock.instant());
    templates.save(template);
    return new TemplateResponse(
        template.getKind(),
        template.getSubjectTemplate(),
        template.getBodyTemplate(),
        template.getUpdatedAt());
  }

  /**
   * Envoie un message fictif tout de suite et dit si le canal fonctionne, sans rien enregistrer.
   */
  public TestResponse test(TestRequest request) {
    String destination = request.destination().trim();
    if (request.channel() != NotificationChannel.EMAIL
        && !SecretResolver.isSupported(destination)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "Indiquez la référence du secret qui contient l'URL du canal (ex. env:TEAMS_NOC_URL)");
    }
    NotificationSender sender = senders.get(request.channel());
    try {
      var message = renderer.renderSample("ALERT_OPENED");
      sender.send(destination, message.subject(), message.body());
      return new TestResponse(true, null);
    } catch (Exception e) {
      return new TestResponse(false, NotificationDispatchService.sanitize(e));
    }
  }

  private HistoryItem item(Notification n) {
    List<NotificationAttempt> done = attempts.findByNotificationIdOrderByNumberAsc(n.getId());
    String lastError =
        done.stream()
            .filter(a -> a.getResult() == SendResult.FAILURE)
            .reduce((first, second) -> second)
            .map(NotificationAttempt::getSanitizedError)
            .orElse(null);
    return new HistoryItem(
        n.getId(),
        n.getCreatedAt(),
        n.getSentAt(),
        n.getUser().getUsername(),
        n.getChannel(),
        n.getDestinationAddress(),
        n.getSubject(),
        n.getState(),
        done.size(),
        lastError);
  }
}

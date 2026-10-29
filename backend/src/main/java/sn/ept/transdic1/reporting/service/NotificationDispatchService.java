package sn.ept.transdic1.reporting.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import sn.ept.transdic1.common.scheduling.JobGuard;
import sn.ept.transdic1.reporting.domain.Notification;
import sn.ept.transdic1.reporting.domain.NotificationAttempt;
import sn.ept.transdic1.reporting.domain.NotificationChannel;
import sn.ept.transdic1.reporting.domain.NotificationState;
import sn.ept.transdic1.reporting.domain.SendResult;
import sn.ept.transdic1.reporting.port.NotificationSender;
import sn.ept.transdic1.reporting.repository.NotificationAttemptRepository;
import sn.ept.transdic1.reporting.repository.NotificationRepository;

/**
 * Worker de la table d'envoi : envoie les notifications en attente, hors de la transaction qui a
 * ouvert l'alerte, sur le canal de chacune. Chaque tentative est tracée ; l'attente grandit entre
 * deux tentatives ; après le nombre maximal de tentatives la notification passe en échec, sans
 * jamais bloquer les suivantes. Les messages ne contiennent ni adresse IP ni secret.
 */
@Service
public class NotificationDispatchService {
  private static final Logger LOG = LoggerFactory.getLogger(NotificationDispatchService.class);

  private final NotificationRepository notifications;
  private final NotificationAttemptRepository attempts;
  private final Map<NotificationChannel, NotificationSender> senders =
      new EnumMap<>(NotificationChannel.class);
  private final MessageTemplateService renderer;
  private final TransactionTemplate transaction;
  private final JobGuard guard;
  private final Clock clock;
  private final int maxAttempts;
  private final Duration backoff;
  private final int batchSize;

  public NotificationDispatchService(
      NotificationRepository notifications,
      NotificationAttemptRepository attempts,
      List<NotificationSender> senderList,
      MessageTemplateService renderer,
      TransactionTemplate transaction,
      JobGuard guard,
      Clock clock,
      @Value("${notification.max-attempts:3}") int maxAttempts,
      @Value("${notification.retry-backoff-seconds:60}") long backoffSeconds,
      @Value("${notification.batch-size:50}") int batchSize) {
    this.notifications = notifications;
    this.attempts = attempts;
    senderList.forEach(sender -> senders.put(sender.channel(), sender));
    this.renderer = renderer;
    this.transaction = transaction;
    this.guard = guard;
    this.clock = clock;
    this.maxAttempts = maxAttempts;
    this.backoff = Duration.ofSeconds(backoffSeconds);
    this.batchSize = batchSize;
  }

  /** Passage planifié : une seule instance envoie à la fois, donc jamais de doublon. */
  @Scheduled(fixedDelayString = "${notification.dispatch-interval-ms:30000}")
  public void scheduledDispatch() {
    guard.run("notification.dispatch", Duration.ofMinutes(5), this::dispatchPending);
  }

  /** Envoie les notifications dues ; retourne le nombre de messages réellement envoyés. */
  public int dispatchPending() {
    var pending =
        notifications
            .findByStateOrderByCreatedAtAsc(NotificationState.PENDING, PageRequest.of(0, batchSize))
            .map(Notification::getId)
            .getContent();
    int sent = 0;
    for (var id : pending) {
      Boolean ok = transaction.execute(status -> attempt(id));
      if (Boolean.TRUE.equals(ok)) {
        sent++;
      }
    }
    return sent;
  }

  /** Une tentative pour une notification, dans sa propre transaction. */
  private boolean attempt(UUID id) {
    Notification notification = notifications.findById(id).orElse(null);
    if (notification == null || notification.getState() != NotificationState.PENDING) {
      return false;
    }
    List<NotificationAttempt> previous =
        attempts.findByNotificationIdOrderByNumberAsc(notification.getId());
    Instant now = clock.instant();
    if (!previous.isEmpty()) {
      var last = previous.get(previous.size() - 1);
      // Attente croissante entre deux tentatives : une panne ne déclenche pas une rafale.
      if (now.isBefore(last.getAttemptedAt().plus(backoff.multipliedBy(previous.size())))) {
        return false;
      }
    }
    int number = previous.size() + 1;
    try {
      NotificationSender sender = senders.get(notification.getChannel());
      if (sender == null) {
        throw new IllegalStateException("Canal non pris en charge");
      }
      sender.send(
          notification.getDestinationAddress(), notification.getSubject(), body(notification));
      attempts.save(new NotificationAttempt(notification, number, SendResult.SUCCESS, now));
      notification.setState(NotificationState.SENT);
      notification.setSentAt(now);
      notifications.save(notification);
      return true;
    } catch (Exception e) {
      var failed = new NotificationAttempt(notification, number, SendResult.FAILURE, now);
      failed.setSanitizedError(sanitize(e));
      attempts.save(failed);
      if (number >= maxAttempts) {
        notification.setState(NotificationState.FAILED);
        notifications.save(notification);
        LOG.warn("Notification {} abandonnée après {} tentatives", notification.getId(), number);
      }
      return false;
    }
  }

  private String body(Notification notification) {
    if (notification.getAlert() == null) {
      return notification.getSubject() + "\n\n— Trans-DIC1, supervision EPT\n";
    }
    return renderer.render(notification.getKind(), notification.getAlert()).body();
  }

  /**
   * Garde la nature de l'erreur sans jamais écrire d'adresse, d'URL, de mot de passe ou de trace.
   */
  static String sanitize(Exception e) {
    String message = e.getMessage() == null ? "" : e.getMessage();
    String clean =
        (e.getClass().getSimpleName() + " : " + message)
            .replaceAll("\\p{Cntrl}", " ")
            .replaceAll("https?://\\S+", "[url]")
            // Un message d'erreur peut citer le serveur interne : on le masque.
            .replaceAll("\\b\\d{1,3}(\\.\\d{1,3}){3}\\b", "[ip]")
            .replaceAll("(?i)(host|hote|hôte|server|serveur)\\s*[:=,]?\\s*\\S+", "$1 [masqué]");
    return clean.length() > 300 ? clean.substring(0, 300) : clean;
  }
}

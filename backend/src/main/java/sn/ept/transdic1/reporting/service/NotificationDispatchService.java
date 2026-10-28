package sn.ept.transdic1.reporting.service;

import jakarta.mail.internet.MimeMessage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import sn.ept.transdic1.alerting.domain.Alert;
import sn.ept.transdic1.alerting.domain.AlertState;
import sn.ept.transdic1.reporting.domain.Notification;
import sn.ept.transdic1.reporting.domain.NotificationAttempt;
import sn.ept.transdic1.reporting.domain.NotificationState;
import sn.ept.transdic1.reporting.domain.SendResult;
import sn.ept.transdic1.reporting.repository.NotificationAttemptRepository;
import sn.ept.transdic1.reporting.repository.NotificationRepository;

/**
 * Envoie les e-mails en attente, hors de la transaction qui a ouvert l'alerte. Chaque tentative est
 * tracée ; après le nombre maximal de tentatives la notification passe en échec, sans jamais
 * bloquer les suivantes. Les messages ne contiennent ni adresse IP ni secret.
 */
@Service
public class NotificationDispatchService {
  private static final Logger LOG = LoggerFactory.getLogger(NotificationDispatchService.class);
  private static final DateTimeFormatter DATE =
      DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm 'UTC'", Locale.FRANCE)
          .withZone(ZoneId.of("UTC"));

  private final NotificationRepository notifications;
  private final NotificationAttemptRepository attempts;
  private final JavaMailSender mailSender;
  private final TransactionTemplate transaction;
  private final Clock clock;
  private final String from;
  private final String publicUrl;
  private final int maxAttempts;
  private final Duration backoff;
  private final int batchSize;

  public NotificationDispatchService(
      NotificationRepository notifications,
      NotificationAttemptRepository attempts,
      JavaMailSender mailSender,
      TransactionTemplate transaction,
      Clock clock,
      @Value("${notification.from}") String from,
      @Value("${notification.public-url}") String publicUrl,
      @Value("${notification.max-attempts:3}") int maxAttempts,
      @Value("${notification.retry-backoff-seconds:60}") long backoffSeconds,
      @Value("${notification.batch-size:50}") int batchSize) {
    this.notifications = notifications;
    this.attempts = attempts;
    this.mailSender = mailSender;
    this.transaction = transaction;
    this.clock = clock;
    this.from = from;
    this.publicUrl = publicUrl;
    this.maxAttempts = maxAttempts;
    this.backoff = Duration.ofSeconds(backoffSeconds);
    this.batchSize = batchSize;
  }

  /** Envoie les notifications dues ; retourne le nombre d'e-mails réellement envoyés. */
  @Scheduled(fixedDelayString = "${notification.dispatch-interval-ms:30000}")
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
  private boolean attempt(java.util.UUID id) {
    Notification notification = notifications.findById(id).orElse(null);
    if (notification == null || notification.getState() != NotificationState.PENDING) {
      return false;
    }
    List<NotificationAttempt> previous =
        attempts.findByNotificationIdOrderByNumberAsc(notification.getId());
    Instant now = clock.instant();
    if (!previous.isEmpty()) {
      var last = previous.get(previous.size() - 1);
      // Attente croissante entre deux tentatives : une panne SMTP ne déclenche pas une rafale.
      if (now.isBefore(last.getAttemptedAt().plus(backoff.multipliedBy(previous.size())))) {
        return false;
      }
    }
    int number = previous.size() + 1;
    try {
      mailSender.send(message(notification));
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

  private MimeMessage message(Notification notification) throws Exception {
    MimeMessage mime = mailSender.createMimeMessage();
    var helper = new MimeMessageHelper(mime, false, "UTF-8");
    helper.setFrom(from);
    helper.setTo(notification.getDestinationAddress());
    helper.setSubject(notification.getSubject());
    helper.setText(body(notification), false);
    return mime;
  }

  private String body(Notification notification) {
    Alert alert = notification.getAlert();
    var text = new StringBuilder();
    if (alert == null) {
      text.append(notification.getSubject());
    } else {
      boolean resolved = alert.getState() == AlertState.RESOLVED;
      text.append(resolved ? "Alerte résolue" : "Alerte ouverte").append("\n\n");
      text.append("Équipement : ").append(alert.getEquipment().getName());
      text.append(" (site : ").append(alert.getEquipment().getSite().getName()).append(")\n");
      text.append("Sévérité : ").append(alert.getSeverity()).append('\n');
      text.append("Message : ").append(alert.getMessage()).append('\n');
      text.append("Déclenchée le : ").append(DATE.format(alert.getTriggeredAt())).append('\n');
      if (resolved && alert.getResolvedAt() != null) {
        text.append("Résolue le : ").append(DATE.format(alert.getResolvedAt())).append('\n');
      }
      text.append("\nConsulter : ").append(publicUrl).append("/alertes\n");
    }
    text.append("\n— Trans-DIC1, supervision EPT\n");
    return text.toString();
  }

  /** Garde la nature de l'erreur sans jamais écrire d'adresse, de mot de passe ou de trace. */
  static String sanitize(Exception e) {
    String message = e.getMessage() == null ? "" : e.getMessage();
    String clean =
        (e.getClass().getSimpleName() + " : " + message)
            .replaceAll("\\p{Cntrl}", " ")
            // Un message d'erreur SMTP peut citer le serveur interne : on le masque.
            .replaceAll("\\b\\d{1,3}(\\.\\d{1,3}){3}\\b", "[ip]")
            .replaceAll("(?i)(host|hote|hôte|server|serveur)\\s*[:=,]?\\s*\\S+", "$1 [masqué]");
    return clean.length() > 300 ? clean.substring(0, 300) : clean;
  }
}

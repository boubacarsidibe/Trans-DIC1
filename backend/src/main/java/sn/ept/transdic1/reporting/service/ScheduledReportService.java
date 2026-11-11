package sn.ept.transdic1.reporting.service;

import jakarta.mail.internet.MimeMessage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import sn.ept.transdic1.common.scheduling.JobGuard;
import sn.ept.transdic1.reporting.domain.ReportFrequency;
import sn.ept.transdic1.reporting.domain.ReportSchedule;
import sn.ept.transdic1.reporting.repository.ReportScheduleRepository;
import sn.ept.transdic1.reporting.repository.ScheduleRecipientRepository;
import sn.ept.transdic1.reporting.repository.ScheduleSiteScopeRepository;

/**
 * Rapports planifiés : à l'heure dite, le rapport de disponibilité de la période écoulée part par
 * e-mail à ses destinataires, en pièce jointe PDF. Personne n'a besoin d'ouvrir la plateforme pour
 * avoir le bilan de la semaine.
 *
 * <p>La période couverte est celle qui vient de s'achever : la veille pour un envoi quotidien, les
 * sept derniers jours pour un envoi hebdomadaire, le mois écoulé pour un envoi mensuel. Un envoi
 * manqué (serveur arrêté) part au prochain passage, une seule fois : la prochaine échéance est
 * recalculée à partir de maintenant, jamais rattrapée en rafale.
 */
@Service
public class ScheduledReportService {
  private static final Logger LOG = LoggerFactory.getLogger(ScheduledReportService.class);
  private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
  private static final DateTimeFormatter FILE_DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

  private final ReportScheduleRepository schedules;
  private final ScheduleRecipientRepository recipients;
  private final ScheduleSiteScopeRepository siteScopes;
  private final ReportService reports;
  private final ReportPdfService pdf;
  private final JavaMailSender mail;
  private final TransactionTemplate transaction;
  private final JobGuard guard;
  private final Clock clock;
  private final ZoneId zone;
  private final String from;
  private final boolean enabled;

  public ScheduledReportService(
      ReportScheduleRepository schedules,
      ScheduleRecipientRepository recipients,
      ScheduleSiteScopeRepository siteScopes,
      ReportService reports,
      ReportPdfService pdf,
      JavaMailSender mail,
      TransactionTemplate transaction,
      JobGuard guard,
      Clock clock,
      @Value("${reporting.schedule.zone:Africa/Dakar}") String zone,
      @Value("${notification.from}") String from,
      @Value("${reporting.schedule.enabled:true}") boolean enabled) {
    this.schedules = schedules;
    this.recipients = recipients;
    this.siteScopes = siteScopes;
    this.reports = reports;
    this.pdf = pdf;
    this.mail = mail;
    this.transaction = transaction;
    this.guard = guard;
    this.clock = clock;
    this.zone = ZoneId.of(zone);
    this.from = from;
    this.enabled = enabled;
  }

  @Scheduled(cron = "${reporting.schedule.cron:0 */5 * * * *}", zone = "UTC")
  public void scheduledScan() {
    if (enabled) {
      guard.run("reporting.schedules", Duration.ofMinutes(15), this::sendDue);
    }
  }

  /** Envoie les rapports échus ; retourne le nombre d'envois faits. */
  public int sendDue() {
    Instant now = clock.instant();
    List<UUID> due =
        transaction.execute(
            status ->
                schedules.findByActiveTrueAndNextRunAtLessThanEqual(now).stream()
                    .map(ReportSchedule::getId)
                    .toList());
    if (due == null || due.isEmpty()) {
      return 0;
    }
    int sent = 0;
    for (UUID id : due) {
      if (sendSafely(id, now)) {
        sent++;
      }
    }
    return sent;
  }

  private boolean sendSafely(UUID scheduleId, Instant now) {
    try {
      return Boolean.TRUE.equals(transaction.execute(status -> sendOne(scheduleId, now)));
    } catch (RuntimeException e) {
      // Un envoi en échec ne doit jamais empêcher les autres de partir.
      LOG.error("Rapport planifié {} en échec : {}", scheduleId, e.toString());
      return false;
    }
  }

  private boolean sendOne(UUID scheduleId, Instant now) {
    ReportSchedule schedule = schedules.findById(scheduleId).orElse(null);
    if (schedule == null || !schedule.isActive()) {
      return false;
    }
    // L'échéance suivante est posée d'abord : même si l'envoi échoue, il ne repart pas en boucle.
    schedule.setNextRunAt(nextRun(schedule, now));
    schedules.save(schedule);

    Period period = period(schedule.getFrequency(), now);
    List<UUID> sites =
        siteScopes.findByScheduleId(scheduleId).stream()
            .map(scope -> scope.getSite().getId())
            .toList();
    List<String> addresses =
        recipients.findByScheduleId(scheduleId).stream()
            .map(recipient -> recipient.getUser())
            .filter(user -> user.isActive())
            .map(user -> user.getEmail())
            .filter(address -> address != null && !address.isBlank())
            .distinct()
            .toList();
    if (addresses.isEmpty()) {
      LOG.info("Rapport planifié {} sans destinataire actif : rien n'est envoyé", scheduleId);
      return false;
    }
    // Un rapport par site demandé, ou un rapport sur tout le parc si aucun site n'est précisé.
    byte[] attachment =
        pdf.availability(
            reports.availability(
                period.from(), period.to(), sites.size() == 1 ? sites.get(0) : null));
    String subject =
        "Trans-DIC1 — " + schedule.getName() + " — " + DAY.format(period.fromDate(zone));
    String body =
        "Rapport de disponibilité du "
            + DAY.format(period.fromDate(zone))
            + " au "
            + DAY.format(period.toDate(zone))
            + ".\n\nLe détail est en pièce jointe.\n\nEnvoi automatique de Trans-DIC1.";
    String fileName = "trans-dic1-disponibilite-" + FILE_DAY.format(period.fromDate(zone)) + ".pdf";
    int delivered = 0;
    for (String address : addresses) {
      if (send(address, subject, body, fileName, attachment)) {
        delivered++;
      }
    }
    LOG.info("Rapport planifié {} envoyé à {} destinataire(s)", scheduleId, delivered);
    return delivered > 0;
  }

  private boolean send(
      String address, String subject, String body, String fileName, byte[] attachment) {
    try {
      MimeMessage message = mail.createMimeMessage();
      var helper = new MimeMessageHelper(message, true, "UTF-8");
      helper.setFrom(from);
      helper.setTo(address);
      helper.setSubject(subject);
      helper.setText(body, false);
      helper.addAttachment(fileName, new ByteArrayResource(attachment), "application/pdf");
      mail.send(message);
      return true;
    } catch (Exception e) {
      // L'adresse ne doit pas apparaître dans les journaux (donnée personnelle).
      LOG.warn("Envoi d'un rapport planifié en échec : {}", e.getClass().getSimpleName());
      return false;
    }
  }

  /** Période couverte : celle qui vient de s'achever, bornée au jour local. */
  public record Period(Instant from, Instant to) {
    public LocalDate fromDate(ZoneId zone) {
      return from.atZone(zone).toLocalDate();
    }

    public LocalDate toDate(ZoneId zone) {
      // Borne exclusive : on affiche le dernier jour couvert.
      return to.atZone(zone).toLocalDate().minusDays(1);
    }
  }

  public Period period(ReportFrequency frequency, Instant now) {
    LocalDate today = now.atZone(zone).toLocalDate();
    LocalDate start =
        switch (frequency) {
          case DAILY -> today.minusDays(1);
          case WEEKLY -> today.minusDays(7);
          case MONTHLY -> today.minusMonths(1);
        };
    return new Period(start.atStartOfDay(zone).toInstant(), today.atStartOfDay(zone).toInstant());
  }

  /** Prochaine échéance : la prochaine occurrence de l'heure dite, après maintenant. */
  public Instant nextRun(ReportSchedule schedule, Instant now) {
    ZonedDateTime local = now.atZone(zone);
    ZonedDateTime candidate = local.toLocalDate().atTime(schedule.getExecutionTime()).atZone(zone);
    while (!candidate.toInstant().isAfter(now)) {
      candidate =
          switch (schedule.getFrequency()) {
            case DAILY -> candidate.plusDays(1);
            case WEEKLY -> candidate.plusWeeks(1);
            case MONTHLY -> candidate.plusMonths(1);
          };
    }
    return candidate.toInstant();
  }

  /** Nom de la fréquence, en français, pour les écrans et les messages. */
  public static String label(ReportFrequency frequency) {
    return switch (frequency) {
      case DAILY -> "quotidien";
      case WEEKLY -> "hebdomadaire";
      case MONTHLY -> "mensuel";
    };
  }
}

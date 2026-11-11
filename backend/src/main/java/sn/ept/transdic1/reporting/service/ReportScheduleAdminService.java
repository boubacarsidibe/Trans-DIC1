package sn.ept.transdic1.reporting.service;

import java.time.Clock;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import sn.ept.transdic1.common.audit.AuditRequested;
import sn.ept.transdic1.inventory.repository.SiteRepository;
import sn.ept.transdic1.reporting.domain.ReportFrequency;
import sn.ept.transdic1.reporting.domain.ReportSchedule;
import sn.ept.transdic1.reporting.domain.ReportType;
import sn.ept.transdic1.reporting.domain.ScheduleRecipient;
import sn.ept.transdic1.reporting.domain.ScheduleSiteScope;
import sn.ept.transdic1.reporting.repository.ReportScheduleRepository;
import sn.ept.transdic1.reporting.repository.ScheduleRecipientRepository;
import sn.ept.transdic1.reporting.repository.ScheduleSiteScopeRepository;
import sn.ept.transdic1.security.repository.UserRepository;

/**
 * Administration des rapports planifiés : qui reçoit quoi, à quelle heure, sur quel périmètre.
 * L'envoi lui-même est le travail de {@link ScheduledReportService}.
 */
@Service
public class ReportScheduleAdminService {
  /** Un destinataire sans adresse ne recevrait rien : la liste reste courte et vérifiée. */
  static final int MAX_RECIPIENTS = 30;

  private final ReportScheduleRepository schedules;
  private final ScheduleRecipientRepository recipients;
  private final ScheduleSiteScopeRepository siteScopes;
  private final UserRepository users;
  private final SiteRepository sites;
  private final ScheduledReportService sender;
  private final ApplicationEventPublisher publisher;
  private final Clock clock;

  public ReportScheduleAdminService(
      ReportScheduleRepository schedules,
      ScheduleRecipientRepository recipients,
      ScheduleSiteScopeRepository siteScopes,
      UserRepository users,
      SiteRepository sites,
      ScheduledReportService sender,
      ApplicationEventPublisher publisher,
      Clock clock) {
    this.schedules = schedules;
    this.recipients = recipients;
    this.siteScopes = siteScopes;
    this.users = users;
    this.sites = sites;
    this.sender = sender;
    this.publisher = publisher;
    this.clock = clock;
  }

  /** Une planification telle que l'écran la montre. */
  public record ScheduleView(
      UUID id,
      String name,
      ReportFrequency frequency,
      String executionTime,
      boolean active,
      java.time.Instant nextRunAt,
      List<UUID> recipientIds,
      List<UUID> siteIds) {}

  /** Ce que l'écran envoie pour créer ou modifier une planification. */
  public record ScheduleRequest(
      String name,
      ReportFrequency frequency,
      String executionTime,
      Boolean active,
      List<UUID> recipientIds,
      List<UUID> siteIds) {}

  @Transactional(readOnly = true)
  public List<ScheduleView> list() {
    return schedules.findAll().stream()
        .sorted(java.util.Comparator.comparing(ReportSchedule::getName))
        .map(this::view)
        .toList();
  }

  @Transactional
  public ScheduleView create(ScheduleRequest request, String actor) {
    var creator =
        users
            .findByUsername(actor)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Compte introuvable"));
    validate(request);
    var schedule =
        new ReportSchedule(
            creator,
            request.name().trim(),
            ReportType.CUSTOM,
            request.frequency(),
            time(request.executionTime()));
    schedule.setActive(request.active() == null || request.active());
    schedule.setNextRunAt(sender.nextRun(schedule, clock.instant()));
    schedules.saveAndFlush(schedule);
    replaceLinks(schedule, request);
    audit(schedule, "REPORT_SCHEDULE_CREATED");
    return view(schedule);
  }

  @Transactional
  public ScheduleView update(UUID id, ScheduleRequest request, String actor) {
    ReportSchedule schedule = find(id);
    validate(request);
    schedule.setName(request.name().trim());
    schedule.setFrequency(request.frequency());
    schedule.setExecutionTime(time(request.executionTime()));
    schedule.setActive(request.active() == null || request.active());
    schedule.setNextRunAt(sender.nextRun(schedule, clock.instant()));
    schedules.saveAndFlush(schedule);
    replaceLinks(schedule, request);
    audit(schedule, "REPORT_SCHEDULE_UPDATED");
    return view(schedule);
  }

  @Transactional
  public void delete(UUID id, String actor) {
    ReportSchedule schedule = find(id);
    recipients.deleteAll(recipients.findByScheduleId(id));
    siteScopes.deleteAll(siteScopes.findByScheduleId(id));
    schedules.delete(schedule);
    audit(schedule, "REPORT_SCHEDULE_DELETED");
  }

  /** Envoi immédiat, pour vérifier la liste des destinataires sans attendre l'heure dite. */
  @Transactional
  public boolean sendNow(UUID id, String actor) {
    ReportSchedule schedule = find(id);
    schedule.setNextRunAt(clock.instant().minusSeconds(1));
    schedules.saveAndFlush(schedule);
    audit(schedule, "REPORT_SCHEDULE_TESTED");
    return sender.sendDue() > 0;
  }

  /**
   * Le module des rapports n'a pas le droit de dépendre de celui de l'audit : il publie la demande,
   * l'audit l'écoute et écrit l'entrée dans la même transaction.
   */
  private void audit(ReportSchedule schedule, String action) {
    publisher.publishEvent(
        new AuditRequested(
            action, "REPORT_SCHEDULE", schedule.getId(), "nom=" + schedule.getName()));
  }

  private void replaceLinks(ReportSchedule schedule, ScheduleRequest request) {
    recipients.deleteAll(recipients.findByScheduleId(schedule.getId()));
    siteScopes.deleteAll(siteScopes.findByScheduleId(schedule.getId()));
    recipients.flush();
    siteScopes.flush();
    for (UUID userId : request.recipientIds()) {
      var user =
          users
              .findById(userId)
              .orElseThrow(
                  () ->
                      new ResponseStatusException(HttpStatus.BAD_REQUEST, "Destinataire inconnu"));
      recipients.save(new ScheduleRecipient(schedule, user));
    }
    if (request.siteIds() != null) {
      for (UUID siteId : request.siteIds()) {
        var site =
            sites
                .findById(siteId)
                .orElseThrow(
                    () -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Site inconnu"));
        siteScopes.save(new ScheduleSiteScope(schedule, site));
      }
    }
  }

  private void validate(ScheduleRequest request) {
    if (request.name() == null || request.name().isBlank() || request.name().length() > 120) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Nom de la planification de 1 à 120 caractères");
    }
    if (request.frequency() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Fréquence manquante");
    }
    if (request.recipientIds() == null || request.recipientIds().isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Indiquez au moins un destinataire");
    }
    if (request.recipientIds().size() > MAX_RECIPIENTS) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, MAX_RECIPIENTS + " destinataires au plus");
    }
    if (request.siteIds() != null && request.siteIds().size() > 1) {
      // Un rapport de disponibilité porte sur un site ou sur tout le parc : pas sur un sous-lot.
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Choisissez un seul site, ou aucun pour tout le parc");
    }
    time(request.executionTime());
  }

  private static LocalTime time(String value) {
    try {
      return LocalTime.parse(value == null ? "" : value.trim());
    } catch (RuntimeException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Heure d'envoi au format HH:MM");
    }
  }

  private ReportSchedule find(UUID id) {
    return schedules
        .findById(id)
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Planification introuvable"));
  }

  private ScheduleView view(ReportSchedule schedule) {
    return new ScheduleView(
        schedule.getId(),
        schedule.getName(),
        schedule.getFrequency(),
        schedule.getExecutionTime().toString(),
        schedule.isActive(),
        schedule.getNextRunAt(),
        recipients.findByScheduleId(schedule.getId()).stream()
            .map(recipient -> recipient.getUser().getId())
            .toList(),
        siteScopes.findByScheduleId(schedule.getId()).stream()
            .map(scope -> scope.getSite().getId())
            .toList());
  }
}

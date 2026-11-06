package sn.ept.transdic1.oncall.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import sn.ept.transdic1.audit.service.AuditService;
import sn.ept.transdic1.common.scope.SiteScope;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.repository.SiteRepository;
import sn.ept.transdic1.oncall.domain.OnCallLevel;
import sn.ept.transdic1.oncall.domain.OnCallRotation;
import sn.ept.transdic1.oncall.domain.OnCallSchedule;
import sn.ept.transdic1.oncall.domain.OnCallShift;
import sn.ept.transdic1.oncall.dto.OnCallDtos.OnCallGenerateRequest;
import sn.ept.transdic1.oncall.dto.OnCallDtos.OnCallGenerateResponse;
import sn.ept.transdic1.oncall.dto.OnCallDtos.OnCallRotationRequest;
import sn.ept.transdic1.oncall.dto.OnCallDtos.OnCallRotationResponse;
import sn.ept.transdic1.oncall.dto.OnCallDtos.OnCallScheduleRequest;
import sn.ept.transdic1.oncall.dto.OnCallDtos.OnCallScheduleResponse;
import sn.ept.transdic1.oncall.dto.OnCallDtos.OnCallShiftRequest;
import sn.ept.transdic1.oncall.dto.OnCallDtos.OnCallShiftResponse;
import sn.ept.transdic1.oncall.dto.OnCallDtos.OnCallSlotResponse;
import sn.ept.transdic1.oncall.repository.OnCallRotationRepository;
import sn.ept.transdic1.oncall.repository.OnCallScheduleRepository;
import sn.ept.transdic1.oncall.repository.OnCallShiftRepository;
import sn.ept.transdic1.security.domain.AppUser;
import sn.ept.transdic1.security.domain.Role;
import sn.ept.transdic1.security.repository.UserRepository;

/**
 * Administration des plannings d'astreinte : plannings, gardes, rotations et génération (ADR 0025).
 * Lecture dans le périmètre de l'appelant (ADR 0018) ; écriture réservée aux administrateurs
 * (contrôlée par le contrôleur), validée et tracée dans le journal d'audit.
 */
@Service
public class OnCallPlanningService {
  static final String DEFAULT_TIMEZONE = "Africa/Dakar";
  private static final Duration MAX_SHIFT = Duration.ofDays(31);
  private static final String NOT_FOUND = "Planning introuvable";

  private final OnCallScheduleRepository schedules;
  private final OnCallShiftRepository shifts;
  private final OnCallRotationRepository rotations;
  private final OnCallRotationService generator;
  private final SiteRepository sites;
  private final UserRepository users;
  private final AuditService audit;
  private final SiteScope scope;
  private final Clock clock;

  public OnCallPlanningService(
      OnCallScheduleRepository schedules,
      OnCallShiftRepository shifts,
      OnCallRotationRepository rotations,
      OnCallRotationService generator,
      SiteRepository sites,
      UserRepository users,
      AuditService audit,
      SiteScope scope,
      Clock clock) {
    this.schedules = schedules;
    this.shifts = shifts;
    this.rotations = rotations;
    this.generator = generator;
    this.sites = sites;
    this.users = users;
    this.audit = audit;
    this.scope = scope;
    this.clock = clock;
  }

  // --- Plannings --------------------------------------------------------------------------

  @Transactional(readOnly = true)
  public List<OnCallScheduleResponse> listSchedules() {
    return schedules.findAllByOrderByNameAsc().stream()
        .filter(this::visible)
        .map(OnCallMapper::schedule)
        .toList();
  }

  @Transactional(readOnly = true)
  public OnCallScheduleResponse getSchedule(UUID id) {
    return OnCallMapper.schedule(visibleSchedule(id));
  }

  @Transactional
  public OnCallScheduleResponse createSchedule(OnCallScheduleRequest request) {
    String name = request.name().trim();
    if (schedules.existsByNameIgnoreCase(name)) {
      throw conflict("Un planning porte déjà ce nom.");
    }
    var schedule = new OnCallSchedule(name, timezone(request.timezone()), clock.instant());
    apply(schedule, request);
    schedules.save(schedule);
    audit.record(
        "ONCALL_SCHEDULE_CREATED", "ONCALL_SCHEDULE", schedule.getId(), describe(schedule));
    return OnCallMapper.schedule(schedule);
  }

  @Transactional
  public OnCallScheduleResponse updateSchedule(UUID id, OnCallScheduleRequest request) {
    var schedule = visibleSchedule(id);
    String name = request.name().trim();
    if (schedules.existsByNameIgnoreCaseAndIdNot(name, id)) {
      throw conflict("Un planning porte déjà ce nom.");
    }
    schedule.setName(name);
    schedule.setTimezone(timezone(request.timezone()));
    apply(schedule, request);
    schedule.touch(clock.instant());
    schedules.save(schedule);
    audit.record("ONCALL_SCHEDULE_UPDATED", "ONCALL_SCHEDULE", id, describe(schedule));
    return OnCallMapper.schedule(schedule);
  }

  @Transactional
  public void deleteSchedule(UUID id) {
    var schedule = visibleSchedule(id);
    schedules.delete(schedule);
    audit.record("ONCALL_SCHEDULE_DELETED", "ONCALL_SCHEDULE", id, "nom=" + schedule.getName());
  }

  private void apply(OnCallSchedule schedule, OnCallScheduleRequest request) {
    schedule.setDescription(
        request.description() == null || request.description().isBlank()
            ? null
            : request.description().trim());
    boolean allSites = !Boolean.FALSE.equals(request.allSites());
    schedule.setAllSites(allSites);
    Set<Site> chosen = new LinkedHashSet<>();
    if (!allSites) {
      if (request.siteIds() == null || request.siteIds().isEmpty()) {
        throw bad("Choisissez au moins un site, ou cochez « tous les sites ».");
      }
      for (UUID siteId : new LinkedHashSet<>(request.siteIds())) {
        chosen.add(sites.findById(siteId).orElseThrow(() -> bad("Site inconnu.")));
      }
    }
    schedule.getSites().clear();
    schedule.getSites().addAll(chosen);
    if (request.active() != null) {
      schedule.setActive(request.active());
    }
  }

  private static String timezone(String requested) {
    if (requested == null || requested.isBlank()) {
      return DEFAULT_TIMEZONE;
    }
    String zone = requested.trim();
    if (!ZoneId.getAvailableZoneIds().contains(zone)) {
      throw bad("Fuseau horaire inconnu : utilisez un identifiant IANA (ex. Africa/Dakar).");
    }
    return zone;
  }

  private static String describe(OnCallSchedule s) {
    return "nom="
        + s.getName()
        + " fuseau="
        + s.getTimezone()
        + (s.isAllSites() ? " tous-sites" : " sites=" + s.getSites().size())
        + (s.isActive() ? "" : " inactif");
  }

  // --- Gardes -----------------------------------------------------------------------------

  /** Gardes qui chevauchent [from, to[ ; par défaut de la veille à 60 jours. */
  @Transactional(readOnly = true)
  public List<OnCallShiftResponse> listShifts(UUID scheduleId, Instant from, Instant to) {
    visibleSchedule(scheduleId);
    Instant now = clock.instant();
    Instant start = from == null ? now.minus(1, ChronoUnit.DAYS) : from;
    Instant end = to == null ? now.plus(60, ChronoUnit.DAYS) : to;
    if (!end.isAfter(start)) {
      throw bad("La fin de la période doit être postérieure à son début.");
    }
    return shifts.findOverlapping(scheduleId, start, end).stream()
        .map(OnCallMapper::shift)
        .toList();
  }

  @Transactional
  public OnCallShiftResponse createShift(
      UUID scheduleId, OnCallShiftRequest request, String username) {
    var schedule = visibleSchedule(scheduleId);
    validateWindow(request.startsAt(), request.endsAt());
    var shift =
        new OnCallShift(
            schedule,
            eligibleUser(request.userId()),
            request.level(),
            request.startsAt(),
            request.endsAt(),
            clock.instant());
    shift.setNote(blankToNull(request.note()));
    shift.setOverride(Boolean.TRUE.equals(request.override()));
    shift.setCreatedBy(username);
    shifts.save(shift);
    audit.record("ONCALL_SHIFT_CREATED", "ONCALL_SHIFT", shift.getId(), describe(shift));
    return OnCallMapper.shift(shift);
  }

  @Transactional
  public OnCallShiftResponse updateShift(
      UUID scheduleId, UUID shiftId, OnCallShiftRequest request) {
    var shift = manualShift(scheduleId, shiftId);
    validateWindow(request.startsAt(), request.endsAt());
    shift.setUser(eligibleUser(request.userId()));
    shift.setLevel(request.level());
    shift.setStartsAt(request.startsAt());
    shift.setEndsAt(request.endsAt());
    shift.setNote(blankToNull(request.note()));
    shift.setOverride(Boolean.TRUE.equals(request.override()));
    shifts.save(shift);
    audit.record("ONCALL_SHIFT_UPDATED", "ONCALL_SHIFT", shiftId, describe(shift));
    return OnCallMapper.shift(shift);
  }

  @Transactional
  public void deleteShift(UUID scheduleId, UUID shiftId) {
    var shift = manualShift(scheduleId, shiftId);
    shifts.delete(shift);
    audit.record("ONCALL_SHIFT_DELETED", "ONCALL_SHIFT", shiftId, describe(shift));
  }

  /** Une garde générée ne se modifie pas une à une : changer la rotation ou la remplacer. */
  private OnCallShift manualShift(UUID scheduleId, UUID shiftId) {
    visibleSchedule(scheduleId);
    var shift =
        shifts
            .findById(shiftId)
            .filter(s -> s.getSchedule().getId().equals(scheduleId))
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Garde introuvable"));
    if (shift.getRotation() != null) {
      throw conflict(
          "Garde générée par la rotation : modifiez la rotation ou créez un remplacement.");
    }
    return shift;
  }

  static void validateWindow(Instant start, Instant end) {
    if (!end.isAfter(start)) {
      throw bad("La fin de la garde doit être postérieure au début.");
    }
    if (Duration.between(start, end).compareTo(MAX_SHIFT) > 0) {
      throw bad("Une garde dure au plus 31 jours : découpez-la ou utilisez une rotation.");
    }
  }

  /** Seuls un administrateur ou un opérateur NOC actifs peuvent être d'astreinte. */
  AppUser eligibleUser(UUID userId) {
    return users
        .findById(userId)
        .filter(AppUser::isActive)
        .filter(u -> u.getRole() == Role.ADMIN || u.getRole() == Role.NOC_OPERATOR)
        .orElseThrow(
            () ->
                bad(
                    "La personne d'astreinte doit être un administrateur ou un opérateur NOC"
                        + " actif."));
  }

  static String describe(OnCallShift s) {
    return "planning="
        + s.getSchedule().getName()
        + " personne="
        + s.getUser().getUsername()
        + " niveau="
        + s.getLevel()
        + " du "
        + s.getStartsAt()
        + " au "
        + s.getEndsAt()
        + (s.isOverride() ? " remplacement" : "");
  }

  // --- Rotations --------------------------------------------------------------------------

  @Transactional(readOnly = true)
  public List<OnCallRotationResponse> listRotations(UUID scheduleId) {
    visibleSchedule(scheduleId);
    return rotations.findByScheduleIdOrderByLevelAsc(scheduleId).stream()
        .map(OnCallMapper::rotation)
        .toList();
  }

  /**
   * Crée ou remplace la rotation d'un niveau. Les gardes futures déjà générées par l'ancienne
   * rotation sont retirées (jamais la garde en cours, le passé ni les remplacements) : il reste à
   * régénérer (aperçu puis confirmation), sinon l'extension nocturne s'en charge.
   */
  @Transactional
  public OnCallRotationResponse saveRotation(UUID scheduleId, OnCallRotationRequest request) {
    var schedule = visibleSchedule(scheduleId);
    Instant now = clock.instant();
    var members = request.userIds().stream().map(this::eligibleUser).toList();
    var rotation =
        rotations
            .findByScheduleIdAndLevel(scheduleId, request.level())
            .orElseGet(() -> new OnCallRotation(schedule, request.level(), now));
    rotation.setMembers(members);
    rotation.setStartDate(request.startDate());
    rotation.setShiftLengthDays(request.shiftLengthDays());
    rotation.setHandoverTime(request.handoverTime().withSecond(0).withNano(0));
    rotation.touch(now);
    rotations.saveAndFlush(rotation);
    int discarded = generator.discardFuture(rotation.getId(), now);
    audit.record(
        "ONCALL_ROTATION_SAVED",
        "ONCALL_ROTATION",
        rotation.getId(),
        "planning="
            + schedule.getName()
            + " niveau="
            + request.level()
            + " personnes="
            + members.size()
            + " duree="
            + request.shiftLengthDays()
            + "j releve="
            + rotation.getHandoverTime()
            + " gardes-futures-retirees="
            + discarded);
    return OnCallMapper.rotation(rotation);
  }

  @Transactional
  public void deleteRotation(UUID scheduleId, OnCallLevel level) {
    visibleSchedule(scheduleId);
    var rotation =
        rotations
            .findByScheduleIdAndLevel(scheduleId, level)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Rotation introuvable"));
    int discarded = generator.discardFuture(rotation.getId(), clock.instant());
    rotations.delete(rotation);
    audit.record(
        "ONCALL_ROTATION_DELETED",
        "ONCALL_ROTATION",
        rotation.getId(),
        "niveau=" + level + " gardes-futures-retirees=" + discarded);
  }

  /** Aperçu (rien n'est écrit) ou génération des gardes de la rotation d'un niveau. */
  @Transactional
  public OnCallGenerateResponse generate(UUID scheduleId, OnCallGenerateRequest request) {
    var schedule = visibleSchedule(scheduleId);
    var rotation =
        rotations
            .findByScheduleIdAndLevel(scheduleId, request.level())
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Aucune rotation pour ce niveau"));
    int horizon = request.horizonDays() == null ? 60 : request.horizonDays();
    boolean preview = Boolean.TRUE.equals(request.preview());
    var slots = generator.generate(rotation.getId(), horizon, clock.instant(), preview);
    if (!preview) {
      audit.record(
          "ONCALL_ROTATION_GENERATED",
          "ONCALL_ROTATION",
          rotation.getId(),
          "planning="
              + schedule.getName()
              + " niveau="
              + request.level()
              + " horizon="
              + horizon
              + "j gardes="
              + slots.size());
    }
    return new OnCallGenerateResponse(
        preview,
        request.level(),
        slots.size(),
        slots.stream()
            .map(s -> new OnCallSlotResponse(s.startsAt(), s.endsAt(), s.userId(), s.username()))
            .toList());
  }

  // --- Commun -----------------------------------------------------------------------------

  OnCallSchedule visibleSchedule(UUID id) {
    return schedules
        .findById(id)
        .filter(this::visible)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, NOT_FOUND));
  }

  /** Un compte limité voit les plannings tous sites et ceux qui couvrent un de ses sites. */
  boolean visible(OnCallSchedule schedule) {
    return scope
        .restriction()
        .map(
            ids ->
                schedule.isAllSites()
                    || schedule.getSites().stream().anyMatch(s -> ids.contains(s.getId())))
        .orElse(true);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  static ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }
}

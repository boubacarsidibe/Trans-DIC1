package sn.ept.transdic1.oncall.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import sn.ept.transdic1.audit.service.AuditService;
import sn.ept.transdic1.common.scope.SiteScope;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.repository.SiteRepository;
import sn.ept.transdic1.oncall.domain.OnCallSchedule;
import sn.ept.transdic1.oncall.domain.OnCallShift;
import sn.ept.transdic1.oncall.dto.OnCallDtos.OnCallHandoverRequest;
import sn.ept.transdic1.oncall.dto.OnCallDtos.OnCallNowResponse;
import sn.ept.transdic1.oncall.dto.OnCallDtos.OnCallShiftResponse;
import sn.ept.transdic1.oncall.dto.OnCallDtos.OnCallUserRef;
import sn.ept.transdic1.oncall.repository.OnCallScheduleRepository;
import sn.ept.transdic1.oncall.repository.OnCallShiftRepository;
import sn.ept.transdic1.security.domain.AppUser;
import sn.ept.transdic1.security.domain.Role;
import sn.ept.transdic1.security.repository.UserRepository;

/**
 * Lecture de l'astreinte pour tous les rôles (qui est d'astreinte maintenant, agenda, mes gardes)
 * et passation d'une garde par son titulaire ou un administrateur (ADR 0025).
 */
@Service
public class OnCallQueryService {
  static final int MY_SHIFTS_DAYS = 60;
  private static final int MAX_AGENDA_DAYS = 62;

  private final OnCallResolver resolver;
  private final OnCallPlanningService planning;
  private final OnCallScheduleRepository schedules;
  private final OnCallShiftRepository shifts;
  private final SiteRepository sites;
  private final UserRepository users;
  private final AuditService audit;
  private final SiteScope scope;
  private final Clock clock;

  public OnCallQueryService(
      OnCallResolver resolver,
      OnCallPlanningService planning,
      OnCallScheduleRepository schedules,
      OnCallShiftRepository shifts,
      SiteRepository sites,
      UserRepository users,
      AuditService audit,
      SiteScope scope,
      Clock clock) {
    this.resolver = resolver;
    this.planning = planning;
    this.schedules = schedules;
    this.shifts = shifts;
    this.sites = sites;
    this.users = users;
    this.audit = audit;
    this.scope = scope;
    this.clock = clock;
  }

  /** Principal et secondaire de chaque site visible, à l'instant présent. */
  @Transactional(readOnly = true)
  public List<OnCallNowResponse> now() {
    Instant now = clock.instant();
    return sites.findAll().stream()
        .filter(site -> scope.allows(site.getId()))
        .sorted(Comparator.comparing(Site::getName))
        .map(
            site -> {
              var resolution = resolver.resolve(site.getId(), now);
              return new OnCallNowResponse(
                  site.getId(),
                  site.getCode(),
                  site.getName(),
                  resolution.covered(),
                  OnCallMapper.person(resolution.primary().orElse(null)),
                  OnCallMapper.person(resolution.secondary().orElse(null)));
            })
        .toList();
  }

  /** Gardes de l'appelant en cours ou à venir dans les 60 jours. */
  @Transactional(readOnly = true)
  public List<OnCallShiftResponse> myShifts(String username) {
    AppUser me = caller(username);
    Instant now = clock.instant();
    return shifts.findForUser(me.getId(), now, now.plus(MY_SHIFTS_DAYS, ChronoUnit.DAYS)).stream()
        .map(OnCallMapper::shift)
        .toList();
  }

  /** Gardes de tous les plannings visibles sur [début, début + jours[ (agenda). */
  @Transactional(readOnly = true)
  public List<OnCallShiftResponse> agenda(Instant from, Integer days) {
    Instant start = from == null ? clock.instant().truncatedTo(ChronoUnit.DAYS) : from;
    int span = days == null ? 30 : days;
    if (span < 1 || span > MAX_AGENDA_DAYS) {
      throw OnCallPlanningService.bad("L'agenda couvre de 1 à " + MAX_AGENDA_DAYS + " jours.");
    }
    var ids =
        schedules.findAllByOrderByNameAsc().stream()
            .filter(planning::visible)
            .map(OnCallSchedule::getId)
            .toList();
    if (ids.isEmpty()) {
      return List.of();
    }
    return shifts.findOverlappingIn(ids, start, start.plus(span, ChronoUnit.DAYS)).stream()
        .map(OnCallMapper::shift)
        .toList();
  }

  /** Personnes qui peuvent être d'astreinte (administrateurs et opérateurs NOC actifs). */
  @Transactional(readOnly = true)
  public List<OnCallUserRef> eligiblePeople() {
    return java.util.stream.Stream.of(Role.ADMIN, Role.NOC_OPERATOR)
        .flatMap(role -> users.findByRoleAndActiveTrue(role).stream())
        .sorted(Comparator.comparing(AppUser::getUsername))
        .map(u -> new OnCallUserRef(u.getId(), u.getUsername()))
        .toList();
  }

  /**
   * Passation : le titulaire de la garde (ou un administrateur) la confie à quelqu'un d'autre. Un
   * remplacement est créé de maintenant (ou du début de la garde, si elle n'a pas commencé) à la
   * fin de la garde ; la garde d'origine reste dans l'historique.
   */
  @Transactional
  public OnCallShiftResponse handover(
      UUID shiftId, OnCallHandoverRequest request, String username) {
    AppUser me = caller(username);
    OnCallShift shift =
        shifts
            .findById(shiftId)
            .filter(s -> planning.visible(s.getSchedule()))
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Garde introuvable"));
    if (me.getRole() != Role.ADMIN && !shift.getUser().getId().equals(me.getId())) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN,
          "Seul le titulaire de la garde ou un administrateur peut la passer.");
    }
    Instant now = clock.instant();
    if (!shift.getEndsAt().isAfter(now)) {
      throw OnCallPlanningService.conflict(
          "Cette garde est terminée : elle ne peut plus être passée.");
    }
    AppUser target = planning.eligibleUser(request.userId());
    if (target.getId().equals(shift.getUser().getId())) {
      throw OnCallPlanningService.bad("Cette personne est déjà titulaire de la garde.");
    }
    Instant start = shift.getStartsAt().isAfter(now) ? shift.getStartsAt() : now;
    var replacement =
        new OnCallShift(
            shift.getSchedule(), target, shift.getLevel(), start, shift.getEndsAt(), now);
    replacement.setOverride(true);
    replacement.setReplacesShiftId(shift.getId());
    String note = request.note() == null || request.note().isBlank() ? null : request.note().trim();
    replacement.setNote(
        "Remplace " + shift.getUser().getUsername() + (note == null ? "" : " : " + note));
    replacement.setCreatedBy(username);
    shifts.save(replacement);
    audit.record(
        "ONCALL_SHIFT_HANDOVER",
        "ONCALL_SHIFT",
        shift.getId(),
        "de="
            + shift.getUser().getUsername()
            + " a="
            + target.getUsername()
            + " remplacement="
            + replacement.getId()
            + " du "
            + start
            + " au "
            + shift.getEndsAt());
    return OnCallMapper.shift(replacement);
  }

  private AppUser caller(String username) {
    return users
        .findByUsername(username)
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Utilisateur inconnu"));
  }
}

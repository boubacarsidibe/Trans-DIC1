package sn.ept.transdic1.oncall.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.oncall.domain.OnCallLevel;
import sn.ept.transdic1.oncall.domain.OnCallSchedule;
import sn.ept.transdic1.oncall.domain.OnCallShift;
import sn.ept.transdic1.oncall.repository.OnCallScheduleRepository;
import sn.ept.transdic1.oncall.repository.OnCallShiftRepository;

/** Qui est d'astreinte pour un site à un instant (ADR 0025). */
@Component
public class OnCallResolver {
  /** Personne d'astreinte retenue et la garde qui l'y met. */
  public record OnCallPerson(
      UUID userId,
      String username,
      UUID shiftId,
      UUID scheduleId,
      String scheduleName,
      Instant startsAt,
      Instant endsAt,
      boolean override) {}

  /**
   * {@code covered} : au moins un planning actif couvre le site, même si personne n'est de garde.
   */
  public record Resolution(
      boolean covered, Optional<OnCallPerson> primary, Optional<OnCallPerson> secondary) {}

  private final OnCallScheduleRepository schedules;
  private final OnCallShiftRepository shifts;

  public OnCallResolver(OnCallScheduleRepository schedules, OnCallShiftRepository shifts) {
    this.schedules = schedules;
    this.shifts = shifts;
  }

  /**
   * Principal et secondaire pour un site à un instant. Pour chaque niveau, les plannings propres au
   * site sont consultés d'abord ; s'ils ne donnent personne à ce niveau, les plannings « tous les
   * sites » prennent le relais. Un équipement sans site n'est couvert que par ces derniers.
   */
  @Transactional(readOnly = true)
  public Resolution resolve(UUID siteId, Instant at) {
    var candidates = candidates(siteId);
    boolean covered = !candidates.specific().isEmpty() || !candidates.global().isEmpty();
    return new Resolution(
        covered,
        resolve(candidates, at, OnCallLevel.PRIMARY),
        resolve(candidates, at, OnCallLevel.SECONDARY));
  }

  @Transactional(readOnly = true)
  public Optional<OnCallPerson> resolve(UUID siteId, Instant at, OnCallLevel level) {
    return resolve(candidates(siteId), at, level);
  }

  private record Candidates(List<UUID> specific, List<UUID> global) {}

  private Candidates candidates(UUID siteId) {
    var specific = new ArrayList<UUID>();
    var global = new ArrayList<UUID>();
    for (OnCallSchedule schedule : schedules.findByActiveTrue()) {
      if (schedule.isAllSites()) {
        global.add(schedule.getId());
      } else if (schedule.covers(siteId)) {
        specific.add(schedule.getId());
      }
    }
    return new Candidates(specific, global);
  }

  private Optional<OnCallPerson> resolve(Candidates candidates, Instant at, OnCallLevel level) {
    for (List<UUID> group : List.of(candidates.specific(), candidates.global())) {
      if (group.isEmpty()) {
        continue;
      }
      var found = pick(shifts.findCovering(group, level, at), at);
      if (found.isPresent()) {
        return found;
      }
    }
    return Optional.empty();
  }

  /**
   * Ordre déterministe : un remplacement d'abord, puis la garde créée le plus récemment, puis
   * l'identifiant (départage stable). Une personne désactivée est ignorée : la garde suivante
   * reprend.
   */
  static Optional<OnCallPerson> pick(List<OnCallShift> covering, Instant at) {
    return covering.stream()
        .filter(s -> s.coversInstant(at))
        .filter(s -> s.getUser().isActive())
        .sorted(PRIORITY)
        .findFirst()
        .map(OnCallResolver::person);
  }

  static final Comparator<OnCallShift> PRIORITY =
      Comparator.comparing(OnCallShift::isOverride)
          .reversed()
          .thenComparing(OnCallShift::getCreatedAt, Comparator.reverseOrder())
          .thenComparing(s -> s.getId().toString());

  static OnCallPerson person(OnCallShift shift) {
    return new OnCallPerson(
        shift.getUser().getId(),
        shift.getUser().getUsername(),
        shift.getId(),
        shift.getSchedule().getId(),
        shift.getSchedule().getName(),
        shift.getStartsAt(),
        shift.getEndsAt(),
        shift.isOverride());
  }
}

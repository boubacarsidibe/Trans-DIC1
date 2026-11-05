package sn.ept.transdic1.oncall.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import sn.ept.transdic1.common.scheduling.JobGuard;
import sn.ept.transdic1.oncall.domain.OnCallRotation;
import sn.ept.transdic1.oncall.domain.OnCallShift;
import sn.ept.transdic1.oncall.repository.OnCallRotationRepository;
import sn.ept.transdic1.oncall.repository.OnCallShiftRepository;
import sn.ept.transdic1.security.domain.AppUser;

/**
 * Génération des gardes d'une rotation (ADR 0025). Chaque créneau commence à l'heure de relève,
 * exprimée dans le fuseau du planning, puis est converti en UTC : une heure de relève inexistante
 * (passage à l'heure d'été) est décalée de la durée du saut, une heure ambiguë (retour à l'heure
 * d'hiver) prend la première occurrence ; les créneaux restent contigus.
 *
 * <p>La génération est idempotente (un créneau par rotation et par début, index unique), ne crée
 * jamais une garde déjà terminée et ne touche jamais un remplacement ni une garde passée.
 */
@Service
public class OnCallRotationService {
  private static final Logger LOG = LoggerFactory.getLogger(OnCallRotationService.class);

  /** Créneau calculé d'une rotation. */
  public record SlotPlan(Instant startsAt, Instant endsAt, UUID userId, String username) {}

  private final OnCallRotationRepository rotations;
  private final OnCallShiftRepository shifts;
  private final JobGuard guard;
  private final Clock clock;
  private final int horizonDays;

  public OnCallRotationService(
      OnCallRotationRepository rotations,
      OnCallShiftRepository shifts,
      JobGuard guard,
      Clock clock,
      @Value("${oncall.rotation.horizon-days:60}") int horizonDays) {
    this.rotations = rotations;
    this.shifts = shifts;
    this.guard = guard;
    this.clock = clock;
    this.horizonDays = Math.max(1, horizonDays);
  }

  /** Extension nocturne : garde l'horizon de toutes les rotations des plannings actifs. */
  @Scheduled(cron = "${oncall.rotation.extend-cron:0 30 2 * * *}", zone = "UTC")
  public void scheduledExtend() {
    guard.run("oncall.rotation.extend", Duration.ofMinutes(10), () -> extendAll(clock.instant()));
  }

  /**
   * Crée (ou, en aperçu, calcule sans rien écrire) les gardes de la rotation jusqu'à {@code
   * horizonDays} jours après {@code now}. Retourne les créneaux créés (ou qui le seraient).
   */
  @Transactional
  public List<SlotPlan> generate(UUID rotationId, int horizon, Instant now, boolean preview) {
    OnCallRotation rotation =
        rotations
            .findById(rotationId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Rotation introuvable"));
    return generate(rotation, horizon, now, preview);
  }

  /** Retourne le nombre de gardes créées par ce passage. */
  @Transactional
  public int extendAll(Instant now) {
    int created = 0;
    for (OnCallRotation rotation : rotations.findByScheduleActiveTrue()) {
      created += generate(rotation, horizonDays, now, false).size();
    }
    if (created > 0) {
      LOG.info("Astreinte : {} garde(s) générée(s) par l'extension des rotations", created);
    }
    return created;
  }

  /**
   * La rotation a changé : ses gardes générées futures (début après {@code now}) sont retirées pour
   * être régénérées. La garde en cours, le passé et les remplacements ne bougent pas.
   */
  @Transactional
  public int discardFuture(UUID rotationId, Instant now) {
    return shifts.deleteFutureGenerated(rotationId, now);
  }

  private List<SlotPlan> generate(
      OnCallRotation rotation, int horizon, Instant now, boolean preview) {
    ZoneId zone = ZoneId.of(rotation.getSchedule().getTimezone());
    Instant until = now.plus(Math.max(1, horizon), ChronoUnit.DAYS);
    var result = new ArrayList<SlotPlan>();
    for (SlotPlan slot : slots(rotation, zone, now, until)) {
      boolean exists =
          slot.startsAt().isBefore(now)
              // Créneau en cours : ne jamais doubler une garde de la rotation déjà en place.
              ? !shifts
                  .findRotationOverlapping(rotation.getId(), slot.startsAt(), slot.endsAt())
                  .isEmpty()
              : shifts.existsByRotationIdAndStartsAt(rotation.getId(), slot.startsAt());
      if (exists) {
        continue;
      }
      result.add(slot);
      if (!preview) {
        AppUser user =
            rotation.getMembers().stream()
                .filter(m -> m.getId().equals(slot.userId()))
                .findFirst()
                .orElseThrow();
        var shift =
            new OnCallShift(
                rotation.getSchedule(),
                user,
                rotation.getLevel(),
                slot.startsAt(),
                slot.endsAt(),
                clock.instant());
        shift.setRotation(rotation);
        shift.setCreatedBy("rotation");
        shifts.save(shift);
      }
    }
    if (!preview) {
      shifts.flush();
    }
    return result;
  }

  /**
   * Créneaux de la rotation qui chevauchent [from, until[ : le créneau k commence le jour {@code
   * startDate + k × durée} à l'heure de relève locale et finit au début du créneau k+1.
   */
  public static List<SlotPlan> slots(
      OnCallRotation rotation, ZoneId zone, Instant from, Instant until) {
    var members = rotation.getMembers();
    int length = rotation.getShiftLengthDays();
    if (members.isEmpty() || length < 1 || !from.isBefore(until)) {
      return List.of();
    }
    LocalDate start = rotation.getStartDate();
    // Premier créneau candidat : un peu avant la date locale de « from » (marge d'un créneau).
    long daysSinceStart = ChronoUnit.DAYS.between(start, from.atZone(zone).toLocalDate());
    long k = Math.max(0, daysSinceStart / length - 1);
    var result = new ArrayList<SlotPlan>();
    while (true) {
      Instant slotStart = slotStart(rotation, zone, k);
      if (!slotStart.isBefore(until)) {
        break;
      }
      Instant slotEnd = slotStart(rotation, zone, k + 1);
      if (slotEnd.isAfter(from)) {
        AppUser user = members.get((int) (k % members.size()));
        result.add(new SlotPlan(slotStart, slotEnd, user.getId(), user.getUsername()));
      }
      k++;
    }
    return result;
  }

  private static Instant slotStart(OnCallRotation rotation, ZoneId zone, long index) {
    LocalDate day = rotation.getStartDate().plusDays(index * rotation.getShiftLengthDays());
    // ZonedDateTime.of : heure inexistante décalée du saut, heure ambiguë = premier décalage.
    return ZonedDateTime.of(day, rotation.getHandoverTime(), zone).toInstant();
  }
}

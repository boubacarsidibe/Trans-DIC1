package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.repository.SiteRepository;
import sn.ept.transdic1.oncall.domain.OnCallLevel;
import sn.ept.transdic1.oncall.domain.OnCallRotation;
import sn.ept.transdic1.oncall.domain.OnCallSchedule;
import sn.ept.transdic1.oncall.domain.OnCallShift;
import sn.ept.transdic1.oncall.repository.OnCallRotationRepository;
import sn.ept.transdic1.oncall.repository.OnCallScheduleRepository;
import sn.ept.transdic1.oncall.repository.OnCallShiftRepository;
import sn.ept.transdic1.oncall.service.OnCallResolver;
import sn.ept.transdic1.oncall.service.OnCallRotationService;
import sn.ept.transdic1.security.domain.AppUser;
import sn.ept.transdic1.security.domain.Role;
import sn.ept.transdic1.security.repository.UserRepository;

/**
 * Génération des gardes d'une rotation : relève à l'heure locale du planning (Africa/Dakar sans
 * heure d'été, Europe/Paris avec), idempotence, passé et remplacements jamais réécrits, extension
 * de l'horizon.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class OnCallRotationTests {
  private static final Instant FAR_PAST = Instant.parse("2000-01-01T00:00:00Z");
  private static final Instant FAR_FUTURE = Instant.parse("2100-01-01T00:00:00Z");

  @Autowired private OnCallRotationService service;
  @Autowired private OnCallResolver resolver;
  @Autowired private OnCallScheduleRepository schedules;
  @Autowired private OnCallRotationRepository rotations;
  @Autowired private OnCallShiftRepository shifts;
  @Autowired private SiteRepository sites;
  @Autowired private UserRepository users;
  @Autowired private EntityManager em;

  private final String suffix = UUID.randomUUID().toString().substring(0, 8);
  private Site site;
  private AppUser a;
  private AppUser b;
  private AppUser c;

  @BeforeEach
  void data() {
    site = sites.save(new Site("OCROT-" + suffix, "Site rotation " + suffix));
    a = user("a");
    b = user("b");
    c = user("c");
  }

  @Test
  void laRotationAlterneLesMembresAvecLaReleveALHeureDuPlanning() {
    var rotation = rotation("Africa/Dakar", LocalDate.of(2031, 5, 1), 7, LocalTime.of(8, 0));

    var created =
        service.generate(rotation.getId(), 21, Instant.parse("2031-05-01T00:00:00Z"), false);

    assertThat(created).hasSize(3);
    assertThat(created)
        .extracting(OnCallRotationService.SlotPlan::userId)
        .containsExactly(a.getId(), b.getId(), c.getId());
    assertThat(created.get(0).startsAt()).isEqualTo(Instant.parse("2031-05-01T08:00:00Z"));
    assertThat(created.get(0).endsAt()).isEqualTo(Instant.parse("2031-05-08T08:00:00Z"));
    assertThat(created.get(2).endsAt()).isEqualTo(Instant.parse("2031-05-22T08:00:00Z"));
    assertThat(shiftsOf(rotation)).hasSize(3).noneMatch(OnCallShift::isOverride);
  }

  @Test
  void lApercuNeCreeRien() {
    var rotation = rotation("Africa/Dakar", LocalDate.of(2031, 5, 1), 7, LocalTime.of(8, 0));

    var preview =
        service.generate(rotation.getId(), 21, Instant.parse("2031-05-01T00:00:00Z"), true);

    assertThat(preview).hasSize(3);
    assertThat(shiftsOf(rotation)).isEmpty();
  }

  @Test
  void laGenerationEstIdempotente() {
    var rotation = rotation("Africa/Dakar", LocalDate.of(2031, 5, 1), 7, LocalTime.of(8, 0));
    Instant now = Instant.parse("2031-05-01T00:00:00Z");

    service.generate(rotation.getId(), 21, now, false);
    var second = service.generate(rotation.getId(), 21, now, false);

    assertThat(second).isEmpty();
    assertThat(shiftsOf(rotation)).hasSize(3);
  }

  @Test
  void leChangementDeRotationNeReecritNiLePasseNiLesRemplacements() {
    var rotation = rotation("Africa/Dakar", LocalDate.of(2031, 5, 1), 7, LocalTime.of(8, 0));
    service.generate(rotation.getId(), 21, Instant.parse("2031-05-01T00:00:00Z"), false);
    // Remplacement ponctuel pendant le 2e créneau.
    var override =
        new OnCallShift(
            rotation.getSchedule(),
            c,
            OnCallLevel.PRIMARY,
            Instant.parse("2031-05-09T08:00:00Z"),
            Instant.parse("2031-05-10T08:00:00Z"),
            Instant.parse("2031-05-02T00:00:00Z"));
    override.setOverride(true);
    shifts.saveAndFlush(override);

    // Le 3 mai (1er créneau en cours), la rotation change : c d'abord, puis a.
    Instant now = Instant.parse("2031-05-03T00:00:00Z");
    rotation.setMembers(List.of(c, a));
    rotations.saveAndFlush(rotation);
    service.discardFuture(rotation.getId(), now);
    service.generate(rotation.getId(), 21, now, false);
    em.flush();
    em.clear();

    var all = shifts.findOverlapping(rotation.getSchedule().getId(), FAR_PAST, FAR_FUTURE);
    // Le créneau en cours garde sa personne (a) ; le remplacement est intact.
    assertThat(all)
        .filteredOn(s -> s.getStartsAt().equals(Instant.parse("2031-05-01T08:00:00Z")))
        .singleElement()
        .satisfies(s -> assertThat(s.getUser().getId()).isEqualTo(a.getId()));
    assertThat(all).filteredOn(OnCallShift::isOverride).hasSize(1);
    // Les créneaux futurs suivent la nouvelle liste (index 1 → a, index 2 → c).
    assertThat(all)
        .filteredOn(s -> !s.isOverride())
        .filteredOn(s -> s.getStartsAt().equals(Instant.parse("2031-05-08T08:00:00Z")))
        .singleElement()
        .satisfies(s -> assertThat(s.getUser().getId()).isEqualTo(a.getId()));
    assertThat(all)
        .filteredOn(s -> s.getStartsAt().equals(Instant.parse("2031-05-15T08:00:00Z")))
        .singleElement()
        .satisfies(s -> assertThat(s.getUser().getId()).isEqualTo(c.getId()));
  }

  @Test
  void uneGardeTermineeNEstJamaisRegeneree() {
    var rotation = rotation("Africa/Dakar", LocalDate.of(2031, 5, 1), 7, LocalTime.of(8, 0));
    // Première génération après la fin du 1er créneau : il est déjà passé, rien n'est créé pour
    // lui.
    var created =
        service.generate(rotation.getId(), 14, Instant.parse("2031-05-09T00:00:00Z"), false);

    assertThat(created)
        .extracting(OnCallRotationService.SlotPlan::startsAt)
        .doesNotContain(Instant.parse("2031-05-01T08:00:00Z"))
        .contains(Instant.parse("2031-05-08T08:00:00Z"));
  }

  @Test
  void lExtensionNocturneGardeSoixanteJoursDAvance() {
    var rotation = rotation("Africa/Dakar", LocalDate.of(2031, 5, 1), 7, LocalTime.of(8, 0));
    Instant now = Instant.parse("2031-05-01T00:00:00Z");

    int first = service.extendAll(now);
    var generated = shiftsOf(rotation);
    assertThat(first).isGreaterThanOrEqualTo(8);
    assertThat(generated).allMatch(s -> s.getStartsAt().isBefore(now.plus(60, ChronoUnit.DAYS)));
    assertThat(generated.stream().map(OnCallShift::getEndsAt).max(Instant::compareTo).orElseThrow())
        .isAfterOrEqualTo(now.plus(60, ChronoUnit.DAYS));

    // Une semaine plus tard, un seul créneau de plus ; relancer le même jour n'ajoute rien.
    int later = service.extendAll(now.plus(7, ChronoUnit.DAYS));
    assertThat(later).isEqualTo(1);
    assertThat(service.extendAll(now.plus(7, ChronoUnit.DAYS))).isZero();
  }

  @Test
  void releveEuropeParis_lePassageALHeureDEteRaccourcitLaGardeDUneHeure() {
    var rotation = rotation("Europe/Paris", LocalDate.of(2026, 3, 28), 1, LocalTime.of(8, 0));

    var created =
        service.generate(rotation.getId(), 3, Instant.parse("2026-03-28T00:00:00Z"), false);

    // 28 mars 08:00 (UTC+1) = 07:00Z ; 29 mars 08:00 (UTC+2) = 06:00Z : garde de 23 h.
    assertThat(created.get(0).startsAt()).isEqualTo(Instant.parse("2026-03-28T07:00:00Z"));
    assertThat(created.get(0).endsAt()).isEqualTo(Instant.parse("2026-03-29T06:00:00Z"));
    assertThat(Duration.between(created.get(0).startsAt(), created.get(0).endsAt()))
        .isEqualTo(Duration.ofHours(23));
    // La relève a lieu à 08:00 heure de Paris : b prend la garde à 06:00Z, pas à 07:00Z.
    assertThat(
            resolver
                .resolve(site.getId(), Instant.parse("2026-03-29T05:59:59Z"), OnCallLevel.PRIMARY)
                .orElseThrow()
                .userId())
        .isEqualTo(a.getId());
    assertThat(
            resolver
                .resolve(site.getId(), Instant.parse("2026-03-29T06:00:00Z"), OnCallLevel.PRIMARY)
                .orElseThrow()
                .userId())
        .isEqualTo(b.getId());
  }

  @Test
  void releveEuropeParis_uneHeureInexistanteOuAmbigueEstPlaceeSansTrou() {
    // 02:30 n'existe pas le 29 mars 2026 à Paris : relève à 03:30 (UTC+2) = 01:30Z.
    var spring = rotation("Europe/Paris", LocalDate.of(2026, 3, 28), 1, LocalTime.of(2, 30));
    var march = service.generate(spring.getId(), 3, Instant.parse("2026-03-28T00:00:00Z"), true);
    assertThat(march.get(1).startsAt()).isEqualTo(Instant.parse("2026-03-29T01:30:00Z"));
    assertContiguous(march);

    // 02:30 existe deux fois le 25 octobre 2026 : la première occurrence (UTC+2) est retenue.
    var autumn =
        rotation(
            "Europe/Paris",
            LocalDate.of(2026, 10, 24),
            1,
            LocalTime.of(2, 30),
            OnCallLevel.SECONDARY);
    var october = service.generate(autumn.getId(), 3, Instant.parse("2026-10-24T00:00:00Z"), true);
    assertThat(october.get(1).startsAt()).isEqualTo(Instant.parse("2026-10-25T00:30:00Z"));
    assertThat(Duration.between(october.get(1).startsAt(), october.get(1).endsAt()))
        .isEqualTo(Duration.ofHours(25));
    assertContiguous(october);
  }

  @Test
  void releveAfricaDakar_toujoursVingtQuatreHeures() {
    var rotation = rotation("Africa/Dakar", LocalDate.of(2026, 3, 28), 1, LocalTime.of(8, 0));

    var created =
        service.generate(rotation.getId(), 3, Instant.parse("2026-03-28T00:00:00Z"), true);

    assertThat(created).hasSize(3);
    assertThat(created)
        .allSatisfy(
            s ->
                assertThat(Duration.between(s.startsAt(), s.endsAt()))
                    .isEqualTo(Duration.ofHours(24)));
    assertThat(created.get(1).startsAt()).isEqualTo(Instant.parse("2026-03-29T08:00:00Z"));
  }

  private static void assertContiguous(List<OnCallRotationService.SlotPlan> plan) {
    for (int i = 1; i < plan.size(); i++) {
      assertThat(plan.get(i).startsAt()).isEqualTo(plan.get(i - 1).endsAt());
    }
  }

  private List<OnCallShift> shiftsOf(OnCallRotation rotation) {
    em.flush();
    return shifts.findOverlapping(rotation.getSchedule().getId(), FAR_PAST, FAR_FUTURE);
  }

  private OnCallRotation rotation(String zone, LocalDate start, int days, LocalTime handover) {
    return rotation(zone, start, days, handover, OnCallLevel.PRIMARY);
  }

  private OnCallRotation rotation(
      String zone, LocalDate start, int days, LocalTime handover, OnCallLevel level) {
    var schedule =
        schedules.save(
            new OnCallSchedule(
                "Rotation " + level + " " + zone + " " + suffix,
                zone,
                Instant.parse("2026-01-01T00:00:00Z")));
    schedule.setAllSites(false);
    schedule.getSites().add(site);
    var rotation = new OnCallRotation(schedule, level, Instant.parse("2026-01-01T00:00:00Z"));
    rotation.setStartDate(start);
    rotation.setShiftLengthDays(days);
    rotation.setHandoverTime(handover);
    rotation.setMembers(List.of(a, b, c));
    return rotations.saveAndFlush(rotation);
  }

  private AppUser user(String prefix) {
    return users.save(
        new AppUser(
            "rot-" + prefix + "-" + suffix,
            "rot-" + prefix + "-" + suffix + "@example.test",
            "x",
            Role.NOC_OPERATOR));
  }
}

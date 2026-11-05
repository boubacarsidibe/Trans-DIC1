package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.Set;
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
import sn.ept.transdic1.oncall.domain.OnCallSchedule;
import sn.ept.transdic1.oncall.domain.OnCallShift;
import sn.ept.transdic1.oncall.repository.OnCallScheduleRepository;
import sn.ept.transdic1.oncall.repository.OnCallShiftRepository;
import sn.ept.transdic1.oncall.service.OnCallResolver;
import sn.ept.transdic1.oncall.service.OnCallResolver.OnCallPerson;
import sn.ept.transdic1.security.domain.AppUser;
import sn.ept.transdic1.security.domain.Role;
import sn.ept.transdic1.security.repository.UserRepository;

/** Résolution de la personne d'astreinte (ADR 0025) : bornes, remplacements, sites, inactifs. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class OnCallResolverTests {
  // Instant fixe, loin des gardes des autres tests.
  private static final Instant T0 = Instant.parse("2031-04-01T08:00:00Z");

  @Autowired private OnCallResolver resolver;
  @Autowired private OnCallScheduleRepository schedules;
  @Autowired private OnCallShiftRepository shifts;
  @Autowired private SiteRepository sites;
  @Autowired private UserRepository users;

  private final String suffix = UUID.randomUUID().toString().substring(0, 8);
  private Site site;
  private Site otherSite;
  private AppUser alice;
  private AppUser bob;
  private AppUser carol;

  @BeforeEach
  void data() {
    site = sites.save(new Site("OCR-" + suffix, "Site astreinte " + suffix));
    otherSite = sites.save(new Site("OCX-" + suffix, "Autre site " + suffix));
    alice = user("alice");
    bob = user("bob");
    carol = user("carol");
  }

  @Test
  void personneDAstreinteQuandAucunPlanning() {
    var resolution = resolver.resolve(site.getId(), T0);
    assertThat(resolution.covered()).isFalse();
    assertThat(resolution.primary()).isEmpty();
    assertThat(resolution.secondary()).isEmpty();
  }

  @Test
  void laFenetreInclutLeDebutEtExclutLaFin() {
    var schedule = schedule("bornes", null);
    shift(schedule, alice, OnCallLevel.PRIMARY, T0, T0.plus(8, ChronoUnit.HOURS), 0, false);

    assertThat(name(resolver.resolve(site.getId(), T0, OnCallLevel.PRIMARY))).isEqualTo(alice);
    assertThat(resolver.resolve(site.getId(), T0.minusSeconds(1), OnCallLevel.PRIMARY)).isEmpty();
    assertThat(
            name(
                resolver.resolve(
                    site.getId(),
                    T0.plus(8, ChronoUnit.HOURS).minusMillis(1),
                    OnCallLevel.PRIMARY)))
        .isEqualTo(alice);
    assertThat(resolver.resolve(site.getId(), T0.plus(8, ChronoUnit.HOURS), OnCallLevel.PRIMARY))
        .isEmpty();
  }

  @Test
  void principalEtSecondaireSontResolusSeparement() {
    var schedule = schedule("niveaux", null);
    shift(schedule, alice, OnCallLevel.PRIMARY, T0, T0.plus(1, ChronoUnit.DAYS), 0, false);
    shift(schedule, bob, OnCallLevel.SECONDARY, T0, T0.plus(1, ChronoUnit.DAYS), 0, false);

    var resolution = resolver.resolve(site.getId(), T0.plusSeconds(60));
    assertThat(resolution.covered()).isTrue();
    assertThat(name(resolution.primary())).isEqualTo(alice);
    assertThat(name(resolution.secondary())).isEqualTo(bob);
  }

  @Test
  void unRemplacementLEmporteSurUneGardeNormaleMemePlusRecente() {
    var schedule = schedule("remplacement", null);
    shift(schedule, bob, OnCallLevel.PRIMARY, T0, T0.plus(2, ChronoUnit.HOURS), 1, true);
    // Garde normale créée après le remplacement : le remplacement reste prioritaire.
    shift(schedule, alice, OnCallLevel.PRIMARY, T0, T0.plus(1, ChronoUnit.DAYS), 5, false);

    assertThat(name(resolver.resolve(site.getId(), T0.plusSeconds(60), OnCallLevel.PRIMARY)))
        .isEqualTo(bob);
    // Après la fin du remplacement, la garde normale reprend.
    assertThat(
            name(resolver.resolve(site.getId(), T0.plus(3, ChronoUnit.HOURS), OnCallLevel.PRIMARY)))
        .isEqualTo(alice);
  }

  @Test
  void deuxGardesNormalesQuiSeChevauchent_laPlusRecenteLEmporte() {
    var schedule = schedule("chevauchement", null);
    shift(schedule, alice, OnCallLevel.PRIMARY, T0, T0.plus(1, ChronoUnit.DAYS), 0, false);
    shift(schedule, bob, OnCallLevel.PRIMARY, T0, T0.plus(1, ChronoUnit.DAYS), 10, false);
    shift(schedule, carol, OnCallLevel.PRIMARY, T0, T0.plus(1, ChronoUnit.DAYS), 5, false);

    // Toujours le même résultat, quel que soit l'ordre d'insertion.
    for (int i = 0; i < 3; i++) {
      assertThat(name(resolver.resolve(site.getId(), T0.plusSeconds(60), OnCallLevel.PRIMARY)))
          .isEqualTo(bob);
    }
  }

  @Test
  void unPlanningPropreAuSiteLEmporteSurUnPlanningTousSites() {
    var global = schedule("global", null);
    var local = schedule("local", Set.of(site));
    shift(global, alice, OnCallLevel.PRIMARY, T0, T0.plus(1, ChronoUnit.DAYS), 10, false);
    shift(local, bob, OnCallLevel.PRIMARY, T0, T0.plus(1, ChronoUnit.DAYS), 0, false);
    shift(global, carol, OnCallLevel.SECONDARY, T0, T0.plus(1, ChronoUnit.DAYS), 0, false);

    var resolution = resolver.resolve(site.getId(), T0.plusSeconds(60));
    assertThat(name(resolution.primary())).isEqualTo(bob);
    assertThat(resolution.primary().orElseThrow().scheduleName()).contains("local");
    // Aucun secondaire dans le planning du site : le planning tous sites prend le relais.
    assertThat(name(resolution.secondary())).isEqualTo(carol);
    // Un autre site n'est couvert que par le planning tous sites.
    assertThat(name(resolver.resolve(otherSite.getId(), T0.plusSeconds(60), OnCallLevel.PRIMARY)))
        .isEqualTo(alice);
  }

  @Test
  void unPlanningLimiteAUnSiteNeCouvrePasLesAutres() {
    var local = schedule("local-seul", Set.of(site));
    shift(local, bob, OnCallLevel.PRIMARY, T0, T0.plus(1, ChronoUnit.DAYS), 0, false);

    assertThat(resolver.resolve(otherSite.getId(), T0.plusSeconds(60)).covered()).isFalse();
    assertThat(resolver.resolve(otherSite.getId(), T0.plusSeconds(60), OnCallLevel.PRIMARY))
        .isEmpty();
    // Un équipement sans site n'est couvert que par un planning tous sites.
    assertThat(resolver.resolve(null, T0.plusSeconds(60)).covered()).isFalse();
  }

  @Test
  void unPlanningInactifNEstPasPrisEnCompte() {
    var schedule = schedule("inactif", null);
    schedule.setActive(false);
    schedules.save(schedule);
    shift(schedule, alice, OnCallLevel.PRIMARY, T0, T0.plus(1, ChronoUnit.DAYS), 0, false);

    var resolution = resolver.resolve(site.getId(), T0.plusSeconds(60));
    assertThat(resolution.covered()).isFalse();
    assertThat(resolution.primary()).isEmpty();
  }

  @Test
  void unUtilisateurDesactiveEstIgnore_laGardeSuivanteReprend() {
    var schedule = schedule("desactive", null);
    shift(schedule, alice, OnCallLevel.PRIMARY, T0, T0.plus(1, ChronoUnit.DAYS), 0, false);
    shift(schedule, bob, OnCallLevel.PRIMARY, T0, T0.plus(1, ChronoUnit.DAYS), 5, true);
    bob.setActive(false);
    users.save(bob);

    assertThat(name(resolver.resolve(site.getId(), T0.plusSeconds(60), OnCallLevel.PRIMARY)))
        .isEqualTo(alice);

    alice.setActive(false);
    users.save(alice);
    var resolution = resolver.resolve(site.getId(), T0.plusSeconds(60));
    assertThat(resolution.covered()).isTrue();
    assertThat(resolution.primary()).isEmpty();
  }

  private AppUser name(Optional<OnCallPerson> person) {
    return person.map(p -> users.findById(p.userId()).orElseThrow()).orElse(null);
  }

  private AppUser user(String prefix) {
    return users.save(
        new AppUser(
            "oc-" + prefix + "-" + suffix,
            "oc-" + prefix + "-" + suffix + "@example.test",
            "x",
            Role.NOC_OPERATOR));
  }

  private OnCallSchedule schedule(String name, Set<Site> limitedTo) {
    var schedule = new OnCallSchedule("Astreinte " + name + " " + suffix, "Africa/Dakar", T0);
    if (limitedTo != null) {
      schedule.setAllSites(false);
      schedule.getSites().addAll(limitedTo);
    }
    return schedules.save(schedule);
  }

  private OnCallShift shift(
      OnCallSchedule schedule,
      AppUser user,
      OnCallLevel level,
      Instant start,
      Instant end,
      int createdMinutesAfter,
      boolean override) {
    var shift =
        new OnCallShift(
            schedule,
            user,
            level,
            start,
            end,
            T0.minus(1, ChronoUnit.DAYS).plus(createdMinutesAfter, ChronoUnit.MINUTES));
    shift.setOverride(override);
    return shifts.saveAndFlush(shift);
  }
}

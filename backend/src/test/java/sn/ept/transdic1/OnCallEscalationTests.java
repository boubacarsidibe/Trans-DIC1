package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.alerting.domain.Alert;
import sn.ept.transdic1.alerting.domain.AlertChangedEvent;
import sn.ept.transdic1.alerting.domain.AlertEventType;
import sn.ept.transdic1.alerting.domain.AlertState;
import sn.ept.transdic1.alerting.domain.AlertType;
import sn.ept.transdic1.alerting.domain.Maintenance;
import sn.ept.transdic1.alerting.domain.MaintenanceState;
import sn.ept.transdic1.alerting.domain.Severity;
import sn.ept.transdic1.alerting.repository.AlertEventRepository;
import sn.ept.transdic1.alerting.repository.AlertRepository;
import sn.ept.transdic1.alerting.repository.MaintenanceRepository;
import sn.ept.transdic1.alerting.service.AlertEscalationService;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.EquipmentStatus;
import sn.ept.transdic1.inventory.domain.EquipmentType;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.SiteRepository;
import sn.ept.transdic1.oncall.domain.OnCallLevel;
import sn.ept.transdic1.oncall.domain.OnCallOutcome;
import sn.ept.transdic1.oncall.domain.OnCallSchedule;
import sn.ept.transdic1.oncall.domain.OnCallShift;
import sn.ept.transdic1.oncall.repository.OnCallAlertStepRepository;
import sn.ept.transdic1.oncall.repository.OnCallScheduleRepository;
import sn.ept.transdic1.oncall.repository.OnCallShiftRepository;
import sn.ept.transdic1.oncall.service.OnCallEscalationService;
import sn.ept.transdic1.reporting.domain.NotificationChannel;
import sn.ept.transdic1.reporting.domain.NotificationPreference;
import sn.ept.transdic1.reporting.repository.NotificationPreferenceRepository;
import sn.ept.transdic1.security.domain.AppUser;
import sn.ept.transdic1.security.domain.Role;
import sn.ept.transdic1.security.repository.UserRepository;

/**
 * Chaîne d'escalade d'astreinte : principal tout de suite, secondaire après le délai, prise en
 * charge et résolution arrêtent tout, chaque niveau une seule fois, silence en maintenance.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class OnCallEscalationTests {
  @Autowired private OnCallEscalationService chain;
  @Autowired private AlertEscalationService adminEscalation;
  @Autowired private ApplicationEventPublisher publisher;
  @Autowired private AlertRepository alerts;
  @Autowired private AlertEventRepository events;
  @Autowired private MaintenanceRepository maintenances;
  @Autowired private OnCallScheduleRepository schedules;
  @Autowired private OnCallShiftRepository shifts;
  @Autowired private OnCallAlertStepRepository steps;
  @Autowired private NotificationPreferenceRepository preferences;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private UserRepository users;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EntityManager em;

  private final String suffix = UUID.randomUUID().toString().substring(0, 8);
  private final Instant now = Instant.now();
  private Site site;
  private AppUser primary;
  private AppUser secondary;
  private OnCallSchedule schedule;

  @BeforeEach
  void data() {
    site = sites.save(new Site("OCE-" + suffix, "Site escalade astreinte " + suffix));
    primary = user("prim", true);
    secondary = user("sec", true);
    schedule = new OnCallSchedule("Escalade " + suffix, "Africa/Dakar", now);
    schedule.setAllSites(false);
    schedule.getSites().add(site);
    schedule = schedules.save(schedule);
    shift(primary, OnCallLevel.PRIMARY);
    shift(secondary, OnCallLevel.SECONDARY);
  }

  @Test
  void aLOuvertureLePrincipalEstPrevenuToutDeSuite() {
    Alert alert = alert(Severity.WARNING, AlertState.OPEN, 0);

    publisher.publishEvent(new AlertChangedEvent(alert.getId(), AlertEventType.OPENED));

    assertThat(onCallNotifications(alert, primary)).isEqualTo(1);
    assertThat(onCallNotifications(alert, secondary)).isZero();
    assertThat(comments(alert))
        .anyMatch(
            c -> c.equals("Astreinte : oc-prim-" + suffix + " prévenu(e) (niveau principal)"));
    assertThat(subjectFor(alert, primary)).startsWith("Vous êtes d'astreinte : alerte");
  }

  @Test
  void leSecondaireEstPrevenuApresLeDelai_plusCourtSiCritique() {
    Alert warning = alert(Severity.WARNING, AlertState.OPEN, 0);
    Alert critical = alert(Severity.CRITICAL, AlertState.OPEN, 0);
    chain.startChain(warning.getId(), now);
    chain.startChain(critical.getId(), now);

    chain.escalateSecondary(now.plus(4, ChronoUnit.MINUTES));
    assertThat(onCallNotifications(warning, secondary)).isZero();
    assertThat(onCallNotifications(critical, secondary)).isZero();

    chain.escalateSecondary(now.plus(5, ChronoUnit.MINUTES).plusSeconds(1));
    assertThat(onCallNotifications(critical, secondary)).isEqualTo(1);
    assertThat(onCallNotifications(warning, secondary)).isZero();

    chain.escalateSecondary(now.plus(10, ChronoUnit.MINUTES).plusSeconds(1));
    assertThat(onCallNotifications(warning, secondary)).isEqualTo(1);
    assertThat(comments(warning))
        .contains("Astreinte : oc-sec-" + suffix + " prévenu(e) (niveau secondaire)");
    assertThat(subjectFor(warning, secondary)).startsWith("[2e niveau] Vous êtes d'astreinte");
  }

  @Test
  void laPriseEnChargeArreteLaChaine() {
    Alert alert = alert(Severity.CRITICAL, AlertState.OPEN, 0);
    chain.startChain(alert.getId(), now);
    alert.setState(AlertState.IN_PROGRESS);
    alerts.saveAndFlush(alert);

    chain.escalateSecondary(now.plus(30, ChronoUnit.MINUTES));

    assertThat(onCallNotifications(alert, secondary)).isZero();
    assertThat(steps.existsByAlertIdAndLevel(alert.getId(), OnCallLevel.SECONDARY)).isFalse();
  }

  @Test
  void laResolutionArreteLaChaine() {
    Alert alert = alert(Severity.CRITICAL, AlertState.OPEN, 0);
    chain.startChain(alert.getId(), now);
    alert.setState(AlertState.RESOLVED);
    alert.setResolvedAt(now.plusSeconds(30));
    alert.setResolutionType(sn.ept.transdic1.alerting.domain.ResolutionType.AUTOMATIC);
    alerts.saveAndFlush(alert);

    chain.escalateSecondary(now.plus(30, ChronoUnit.MINUTES));

    assertThat(onCallNotifications(alert, secondary)).isZero();
  }

  @Test
  void chaqueNiveauNeSeDeclencheQuUneFoisMemeSiLePassageTourneDeuxFois() {
    Alert alert = alert(Severity.WARNING, AlertState.OPEN, 0);

    assertThat(chain.startChain(alert.getId(), now)).isTrue();
    assertThat(chain.startChain(alert.getId(), now)).isFalse();
    chain.escalateSecondary(now.plus(11, ChronoUnit.MINUTES));
    chain.escalateSecondary(now.plus(12, ChronoUnit.MINUTES));

    assertThat(onCallNotifications(alert, primary)).isEqualTo(1);
    assertThat(onCallNotifications(alert, secondary)).isEqualTo(1);
    assertThat(steps.findByAlertIdOrderByOccurredAtAsc(alert.getId())).hasSize(2);
    assertThat(comments(alert)).filteredOn(c -> c.startsWith("Astreinte")).hasSize(2);
  }

  @Test
  void rienPendantUneMaintenance() {
    Alert alert = alert(Severity.CRITICAL, AlertState.OPEN, 0);
    maintenance(now.minus(1, ChronoUnit.HOURS), now.plus(1, ChronoUnit.HOURS));

    assertThat(chain.startChain(alert.getId(), now)).isFalse();

    assertThat(onCallNotifications(alert, primary)).isZero();
    assertThat(steps.findByAlertIdOrderByOccurredAtAsc(alert.getId())).isEmpty();
  }

  @Test
  void leSecondaireAttendLaFinDeLaMaintenance() {
    Alert alert = alert(Severity.CRITICAL, AlertState.OPEN, 0);
    chain.startChain(alert.getId(), now);
    maintenance(now.plus(1, ChronoUnit.MINUTES), now.plus(20, ChronoUnit.MINUTES));

    chain.escalateSecondary(now.plus(10, ChronoUnit.MINUTES));
    assertThat(onCallNotifications(alert, secondary)).isZero();

    chain.escalateSecondary(now.plus(21, ChronoUnit.MINUTES));
    assertThat(onCallNotifications(alert, secondary)).isEqualTo(1);
  }

  @Test
  void uneAlerteSousLeSeuilDeSeveriteNeDeclencheRien() {
    Alert alert = alert(Severity.INFO, AlertState.OPEN, 0);

    assertThat(chain.startChain(alert.getId(), now)).isFalse();
    chain.escalateSecondary(now.plus(30, ChronoUnit.MINUTES));

    assertThat(onCallNotifications(alert, primary)).isZero();
    assertThat(onCallNotifications(alert, secondary)).isZero();
  }

  @Test
  void unPrincipalSansCanalEstSignaleEtLeSecondaireEstPrevenuSansAttendre() {
    preferences.delete(preferences.findByUserId(primary.getId()).orElseThrow());
    preferences.flush();
    Alert alert = alert(Severity.WARNING, AlertState.OPEN, 0);

    chain.startChain(alert.getId(), now);

    assertThat(onCallNotifications(alert, primary)).isZero();
    assertThat(comments(alert))
        .anyMatch(c -> c.startsWith("Astreinte sans canal : oc-prim-" + suffix));
    assertThat(steps.findByAlertIdOrderByOccurredAtAsc(alert.getId()))
        .extracting(s -> s.getOutcome())
        .containsExactly(OnCallOutcome.NO_CHANNEL, OnCallOutcome.NOTIFIED);
    // Le secondaire n'a pas attendu le délai.
    assertThat(onCallNotifications(alert, secondary)).isEqualTo(1);
  }

  @Test
  void sansPlanningRienNeChange() {
    schedule.setActive(false);
    schedules.saveAndFlush(schedule);
    Alert alert = alert(Severity.CRITICAL, AlertState.OPEN, 0);

    assertThat(chain.startChain(alert.getId(), now)).isFalse();
    chain.escalateSecondary(now.plus(30, ChronoUnit.MINUTES));

    assertThat(steps.findByAlertIdOrderByOccurredAtAsc(alert.getId())).isEmpty();
    assertThat(comments(alert)).noneMatch(c -> c.startsWith("Astreinte"));
  }

  @Test
  void personneDAstreinteAuNiveauPrincipal_estTraceEtLeSecondairePrendLeRelais() {
    jdbc.update("DELETE FROM oncall_shift WHERE user_id = ?", primary.getId());
    Alert alert = alert(Severity.WARNING, AlertState.OPEN, 0);

    chain.startChain(alert.getId(), now);

    assertThat(comments(alert)).contains("Astreinte : personne d'astreinte au niveau principal");
    assertThat(onCallNotifications(alert, secondary)).isEqualTo(1);
  }

  @Test
  void lEscaladeAuxAdministrateursResteLeDernierNiveau() {
    Alert alert = alert(Severity.WARNING, AlertState.OPEN, 45);
    chain.startChain(alert.getId(), now);
    chain.escalateSecondary(now);

    adminEscalation.escalateOverdue();
    em.flush();

    assertThat(alerts.findById(alert.getId()).orElseThrow().getEscalatedAt()).isNotNull();
    assertThat(onCallNotifications(alert, secondary)).isEqualTo(1);
  }

  private long onCallNotifications(Alert alert, AppUser user) {
    em.flush();
    return jdbc.queryForObject(
        "SELECT count(*) FROM notification WHERE alert_id = ? AND user_id = ? AND kind = 'ALERT_ONCALL'",
        Long.class,
        alert.getId(),
        user.getId());
  }

  private String subjectFor(Alert alert, AppUser user) {
    em.flush();
    return jdbc.queryForObject(
        "SELECT subject FROM notification WHERE alert_id = ? AND user_id = ? AND kind = 'ALERT_ONCALL'",
        String.class,
        alert.getId(),
        user.getId());
  }

  private List<String> comments(Alert alert) {
    em.flush();
    return events.findByAlertIdOrderByOccurredAtAsc(alert.getId()).stream()
        .filter(e -> e.getType() == AlertEventType.NOTIFIED)
        .map(e -> e.getComment())
        .toList();
  }

  private void maintenance(Instant start, Instant end) {
    var m = new Maintenance(primary, start, end, "Travaux", MaintenanceState.PLANNED);
    m.setSite(site);
    maintenances.saveAndFlush(m);
  }

  private Alert alert(Severity severity, AlertState state, long minutesAgo) {
    String unique = UUID.randomUUID().toString();
    Equipment equipment =
        equipments.save(
            new Equipment(
                "OCE-EQ-" + unique,
                "Routeur d'astreinte",
                "10.239."
                    + (Math.floorMod(unique.hashCode(), 250) + 1)
                    + "."
                    + (Math.floorMod(unique.substring(9).hashCode(), 250) + 1),
                EquipmentType.ROUTER,
                EquipmentStatus.UP,
                site));
    return alerts.saveAndFlush(
        new Alert(
            equipment,
            "oce-" + unique,
            AlertType.COLLECTION,
            state,
            severity,
            "Test d'astreinte",
            now.minus(minutesAgo, ChronoUnit.MINUTES)));
  }

  private void shift(AppUser user, OnCallLevel level) {
    shifts.saveAndFlush(
        new OnCallShift(
            schedule,
            user,
            level,
            now.minus(1, ChronoUnit.DAYS),
            now.plus(1, ChronoUnit.DAYS),
            now.minus(2, ChronoUnit.DAYS)));
  }

  private AppUser user(String prefix, boolean withChannel) {
    AppUser user =
        users.save(
            new AppUser(
                "oc-" + prefix + "-" + suffix,
                "oc-" + prefix + "-" + suffix + "@example.test",
                "x",
                Role.NOC_OPERATOR));
    if (withChannel) {
      preferences.save(
          new NotificationPreference(user, NotificationChannel.EMAIL, Severity.CRITICAL));
    }
    return user;
  }
}

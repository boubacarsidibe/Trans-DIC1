package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.alerting.service.ErrorBudgetService;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.EquipmentStatus;
import sn.ept.transdic1.inventory.domain.EquipmentType;
import sn.ept.transdic1.inventory.domain.MonitoredService;
import sn.ept.transdic1.inventory.domain.ServiceType;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.MonitoredServiceRepository;
import sn.ept.transdic1.inventory.repository.SiteRepository;

/**
 * Budget d'erreur des services (M8) sur une vraie base : ce qui ouvre une alerte, ce qui n'en ouvre
 * pas, et la différence entre « le mois est perdu » et « le mois va être perdu ».
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ErrorBudgetAlertTests {
  @Autowired private ErrorBudgetService budgets;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private MonitoredServiceRepository services;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private jakarta.persistence.EntityManager em;

  private Equipment server;
  private MonitoredService probe;
  private Instant now;

  @BeforeEach
  void data() {
    String unique = UUID.randomUUID().toString().substring(0, 8);
    Site site = sites.saveAndFlush(new Site("BUD-" + unique, "Site budget"));
    server =
        equipments.saveAndFlush(
            new Equipment(
                "BUD-EQ-" + UUID.randomUUID(),
                "Serveur applicatif",
                "10.94." + (Math.floorMod(unique.hashCode(), 250) + 1) + ".1",
                EquipmentType.SERVER,
                EquipmentStatus.UP,
                site));
    probe = new MonitoredService(server, "Site web " + unique, ServiceType.HTTP);
    probe.setAccessPoint("https://exemple.test");
    probe.setActive(true);
    probe.setIntervalSeconds(60);
    probe.setSlaTarget(new BigDecimal("0.99500"));
    services.saveAndFlush(probe);
    em.flush();
    now = Instant.now();
  }

  /**
   * Relevés d'une sonde à la minute sur {@code minutes} minutes, les {@code failed} derniers en
   * échec, insérés en bloc.
   *
   * <p>La densité compte : la disponibilité est la part de relevés en échec, et un jeu
   * sous-échantillonné ferait peser chaque échec six minutes au lieu d'une. C'est l'erreur qu'une
   * première version de ces tests contenait.
   */
  private void checks(long minutes, long failed, long offset) {
    jdbc.update(
        "INSERT INTO metric(equipment_id, service_id, metric_key, metric_value, unit, quality,"
            + " collected_at, received_at)"
            + " SELECT CAST(? AS uuid), CAST(? AS uuid), 'probe.up',"
            + " CASE WHEN i > CAST(? AS bigint)"
            + " AND i <= CAST(? AS bigint) + CAST(? AS bigint) THEN 0 ELSE 100 END, '%', 'GOOD',"
            + " CAST(? AS timestamptz) - (i || ' minutes')::interval,"
            + " CAST(? AS timestamptz) - (i || ' minutes')::interval"
            + " FROM generate_series(1, CAST(? AS bigint)) i",
        server.getId(),
        probe.getId(),
        offset,
        offset,
        failed,
        Timestamp.from(now),
        Timestamp.from(now),
        minutes);
    em.flush();
  }

  /**
   * Trente jours de relevés, avec {@code failed} minutes en échec <b>il y a dix jours</b> : loin de
   * la fenêtre de vitesse, pour ne mesurer que le budget dépensé.
   */
  private void month(long failed) {
    checks(Duration.ofDays(30).toMinutes() - 1, failed, Duration.ofDays(10).toMinutes());
  }

  /**
   * Trente jours impeccables, sauf les {@code failed} dernières minutes : le budget est presque
   * intact, mais la vitesse est celle d'une coupure en cours.
   */
  private void monthEndingBadly(long failed) {
    checks(Duration.ofDays(30).toMinutes() - 1, failed, 0);
  }

  private List<String> alertMessages() {
    em.flush();
    return jdbc.queryForList(
        "SELECT message FROM alert WHERE service_id = ? AND state = 'OPEN'"
            + " AND occurrence_key LIKE 'budget:%'",
        String.class, probe.getId());
  }

  @Test
  @DisplayName("Un service qui tient son objectif n'ouvre rien")
  void aserviceMeetingItsTargetOpensNothing() {
    // 0,2 % d'échecs pour un budget de 0,5 % : moins de la moitié dépensée.
    month(20);

    assertThat(budgets.examine(probe, now)).isZero();
    assertThat(alertMessages()).isEmpty();
  }

  @Test
  @DisplayName("Un objectif manqué ouvre une alerte critique qui dit le constat")
  void amissedTargetOpensAcriticalAlert() {
    // 1,6 % d'échecs : plus de trois fois le budget.
    month(400);

    int opened = budgets.examine(probe, now);

    assertThat(opened).isEqualTo(1);
    assertThat(alertMessages())
        .hasSize(1)
        .first(org.assertj.core.api.InstanceOfAssertFactories.STRING)
        .startsWith("Objectif de disponibilité manqué pour")
        .contains("alors que l'objectif est 99,50 %")
        .contains("Le mois est déjà perdu");
    assertThat(
            jdbc.queryForObject(
                "SELECT severity FROM alert WHERE service_id = ? AND occurrence_key LIKE 'budget:%'",
                String.class, probe.getId()))
        .isEqualTo("CRITICAL");
  }

  @Test
  @DisplayName("Ce que M8 apporte : prévenir que le mois va être perdu, avant qu'il le soit")
  void whatM8addsWarningBeforeTheMonthIsLost() {
    // Vingt jours impeccables : le budget est presque intact. Puis la dernière heure part très
    // vite. Un simple pourcentage mensuel ne verrait rien — c'est tout l'intérêt du taux.
    monthEndingBadly(40);

    int opened = budgets.examine(probe, now);

    assertThat(opened).isEqualTo(1);
    assertThat(alertMessages())
        .hasSize(1)
        .first(org.assertj.core.api.InstanceOfAssertFactories.STRING)
        .contains("trop vite")
        .contains("C'est une prévision, pas un constat");
    // Avertissement et non critique : le mois n'est pas encore perdu.
    assertThat(
            jdbc.queryForObject(
                "SELECT severity FROM alert WHERE service_id = ? AND occurrence_key LIKE 'budget:%'",
                String.class, probe.getId()))
        .isEqualTo("WARNING");
  }

  @Test
  @DisplayName("Une coupure de trois minutes ne réveille personne")
  void athreeMinuteOutageWakesNobody() {
    monthEndingBadly(3);

    // Trois relevés en échec sur soixante : la vitesse est de dix fois, sous le seuil de 14,4.
    assertThat(budgets.examine(probe, now)).isZero();
    assertThat(alertMessages()).isEmpty();
  }

  @Test
  @DisplayName("Sans objectif, aucun budget n'est calculé")
  void withoutAtargetNobudgetIsComputed() {
    probe.setSlaTarget(null);
    services.saveAndFlush(probe);
    em.flush();
    month(5_000);

    assertThat(budgets.budget(probe.getId())).isEmpty();
    assertThat(budgets.scan()).isZero();
  }

  @Test
  @DisplayName("Sans assez de relevés, le constat n'est pas rendu")
  void withoutEnoughSamplesTheVerdictIsNotGiven() {
    checks(20, 10, 0);

    var verdict = budgets.budget(probe.getId()).orElseThrow();

    assertThat(verdict.status().measurable()).isFalse();
    assertThat(verdict.exhausted()).isFalse();
  }

  @Test
  @DisplayName("Le retour à une vitesse soutenable referme l'alerte")
  void thereturnToAsustainableSpeedClosesThealert() {
    monthEndingBadly(40);
    budgets.examine(probe, now);
    assertThat(alertMessages()).hasSize(1);

    // La coupure s'arrête : on efface la dernière heure et on la remplace par du calme.
    jdbc.update(
        "DELETE FROM metric WHERE service_id = ? AND collected_at >= ?",
        probe.getId(),
        Timestamp.from(now.minusSeconds(3_600)));
    jdbc.update(
        "INSERT INTO metric(equipment_id, service_id, metric_key, metric_value, unit, quality,"
            + " collected_at, received_at)"
            + " SELECT CAST(? AS uuid), CAST(? AS uuid), 'probe.up', 100, '%', 'GOOD',"
            + " CAST(? AS timestamptz) - (i || ' minutes')::interval,"
            + " CAST(? AS timestamptz) - (i || ' minutes')::interval"
            + " FROM generate_series(1, 59) i",
        server.getId(), probe.getId(), Timestamp.from(now), Timestamp.from(now));
    em.flush();
    budgets.examine(probe, now);

    assertThat(alertMessages()).isEmpty();
  }

  @Test
  @DisplayName("Le budget est rendu à l'API avec ce qui reste et la vitesse")
  void thebudgetIsReturnedWithWhatRemainsAndTheSpeed() {
    month(216);

    var verdict = budgets.budget(probe.getId()).orElseThrow();

    assertThat(verdict.status().measurable()).isTrue();
    assertThat(verdict.status().consumed())
        .isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.05));
    assertThat(verdict.status().remainingMinutes()).isLessThan(20);
  }

  @Test
  @DisplayName("Le minimum de relevés pour une vitesse suit l'intervalle de la sonde")
  void theminimumSamplesForAspeedFollowsTheProbeInterval() {
    probe.setIntervalSeconds(300);

    // Une sonde toutes les cinq minutes ne fournira jamais soixante relevés en une heure :
    // exiger un nombre fixe l'exclurait du calcul pour toujours.
    assertThat(ErrorBudgetService.minimumBurnSamples(probe)).isEqualTo(6);
    probe.setIntervalSeconds(60);
    assertThat(ErrorBudgetService.minimumBurnSamples(probe)).isEqualTo(30);
  }
}

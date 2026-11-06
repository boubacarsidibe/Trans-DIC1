package sn.ept.transdic1.statuspage;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import sn.ept.transdic1.statuspage.domain.ComponentState;
import sn.ept.transdic1.statuspage.service.AvailabilityCalculator;
import sn.ept.transdic1.statuspage.service.AvailabilityCalculator.HourBucket;
import sn.ept.transdic1.statuspage.service.ComponentStateEvaluator;
import sn.ept.transdic1.statuspage.service.ComponentStateEvaluator.AlertFact;
import sn.ept.transdic1.statuspage.service.ComponentStateEvaluator.EquipmentFacts;
import sn.ept.transdic1.statuspage.service.ComponentStateEvaluator.ProbeFacts;
import sn.ept.transdic1.statuspage.service.ComponentStateEvaluator.Window;

/**
 * Calcul déterministe de l'état d'un composant et de la disponibilité journalière (ADR 0026), sans
 * base de données : matrice sonde / alertes / maintenance, agrégation d'un site, état global d'une
 * page, jours sans donnée et maintenance exclue.
 */
class StatusComputationTests {
  private static final Instant NOW = Instant.parse("2026-11-06T12:00:00Z");

  private static ProbeFacts probe(Boolean up, int failures, Instant checkedAt) {
    return new ProbeFacts(true, true, up, checkedAt, failures, 60);
  }

  private static ComponentState probeState(ProbeFacts p, List<AlertFact> alerts, boolean maint) {
    return ComponentStateEvaluator.probe(p, maint, alerts, NOW);
  }

  @Nested
  class Sonde {
    @Test
    void enLigneEtFraicheDonneOperationnel() {
      assertThat(probeState(probe(true, 0, NOW.minusSeconds(30)), List.of(), false))
          .isEqualTo(ComponentState.OPERATIONAL);
    }

    @Test
    void troisEchecsConsecutifsDonnentPanneMajeure() {
      assertThat(probeState(probe(false, 3, NOW.minusSeconds(30)), List.of(), false))
          .isEqualTo(ComponentState.MAJOR_OUTAGE);
    }

    @Test
    void unOuDeuxEchecsDonnentDegrade_instable() {
      assertThat(probeState(probe(false, 1, NOW.minusSeconds(30)), List.of(), false))
          .isEqualTo(ComponentState.DEGRADED);
      assertThat(probeState(probe(false, 2, NOW.minusSeconds(30)), List.of(), false))
          .isEqualTo(ComponentState.DEGRADED);
    }

    @Test
    void sansDonneeOuIndetermineeDonneInconnu() {
      assertThat(probeState(probe(null, 0, null), List.of(), false))
          .isEqualTo(ComponentState.UNKNOWN);
      assertThat(probeState(probe(null, 0, NOW.minusSeconds(30)), List.of(), false))
          .isEqualTo(ComponentState.UNKNOWN);
    }

    @Test
    void resultatPerimeDonneInconnu() {
      // Intervalle 60 s : périmé au-delà de max(3 x 60 s, 5 min) = 5 min.
      assertThat(probeState(probe(true, 0, NOW.minusSeconds(299)), List.of(), false))
          .isEqualTo(ComponentState.OPERATIONAL);
      assertThat(probeState(probe(true, 0, NOW.minusSeconds(301)), List.of(), false))
          .isEqualTo(ComponentState.UNKNOWN);
    }

    @Test
    void sondeInactiveOuEquipementNonSuperviseDonneInconnu() {
      var inactive = new ProbeFacts(false, true, true, NOW, 0, 60);
      var unmonitored = new ProbeFacts(true, false, true, NOW, 0, 60);
      assertThat(probeState(inactive, List.of(), false)).isEqualTo(ComponentState.UNKNOWN);
      assertThat(probeState(unmonitored, List.of(), false)).isEqualTo(ComponentState.UNKNOWN);
    }

    @Test
    void laMaintenancePrimeSurLaPanne() {
      assertThat(
              probeState(
                  probe(false, 5, NOW), List.of(new AlertFact("COLLECTION", "CRITICAL")), true))
          .isEqualTo(ComponentState.MAINTENANCE);
    }

    @Test
    void uneAlerteAggraveUneSondeEnLigne() {
      var ok = probe(true, 0, NOW);
      assertThat(probeState(ok, List.of(new AlertFact("THRESHOLD", "WARNING")), false))
          .isEqualTo(ComponentState.DEGRADED);
      assertThat(probeState(ok, List.of(new AlertFact("THRESHOLD", "CRITICAL")), false))
          .isEqualTo(ComponentState.PARTIAL_OUTAGE);
      assertThat(probeState(ok, List.of(new AlertFact("COLLECTION", "WARNING")), false))
          .isEqualTo(ComponentState.MAJOR_OUTAGE);
      assertThat(probeState(ok, List.of(new AlertFact("THRESHOLD", "INFO")), false))
          .isEqualTo(ComponentState.OPERATIONAL);
    }

    @Test
    void uneAlerteRendMesurableUneSondeSansDonnee() {
      assertThat(
              probeState(
                  probe(null, 0, null), List.of(new AlertFact("THRESHOLD", "CRITICAL")), false))
          .isEqualTo(ComponentState.PARTIAL_OUTAGE);
      assertThat(
              probeState(probe(null, 0, null), List.of(new AlertFact("THRESHOLD", "INFO")), false))
          .isEqualTo(ComponentState.UNKNOWN);
    }

    @Test
    void lePireDeLaSondeEtDesAlertesLEmporte() {
      assertThat(
              probeState(
                  probe(false, 4, NOW), List.of(new AlertFact("THRESHOLD", "WARNING")), false))
          .isEqualTo(ComponentState.MAJOR_OUTAGE);
    }
  }

  @Nested
  class FenetreDeMaintenance {
    private final Window window = new Window(NOW, NOW.plus(1, ChronoUnit.HOURS));

    @Test
    void debutInclusFinExclue() {
      assertThat(ComponentStateEvaluator.covers(List.of(window), NOW)).isTrue();
      assertThat(ComponentStateEvaluator.covers(List.of(window), NOW.minusMillis(1))).isFalse();
      assertThat(ComponentStateEvaluator.covers(List.of(window), NOW.plus(1, ChronoUnit.HOURS)))
          .isFalse();
      assertThat(
              ComponentStateEvaluator.covers(
                  List.of(window), NOW.plus(1, ChronoUnit.HOURS).minusMillis(1)))
          .isTrue();
    }
  }

  @Nested
  class Equipement {
    @ParameterizedTest
    @CsvSource({
      "UP, OPERATIONAL",
      "DEGRADED, DEGRADED",
      "DOWN, MAJOR_OUTAGE",
      "UNKNOWN, UNKNOWN",
      "MAINTENANCE, MAINTENANCE"
    })
    void statutOperationnel(String status, ComponentState expected) {
      assertThat(
              ComponentStateEvaluator.equipment(new EquipmentFacts(true, status), false, List.of()))
          .isEqualTo(expected);
    }

    @Test
    void nonSuperviseDonneInconnuEtMaintenanceDonneMaintenance() {
      assertThat(
              ComponentStateEvaluator.equipment(new EquipmentFacts(false, "UP"), false, List.of()))
          .isEqualTo(ComponentState.UNKNOWN);
      assertThat(
              ComponentStateEvaluator.equipment(new EquipmentFacts(true, "DOWN"), true, List.of()))
          .isEqualTo(ComponentState.MAINTENANCE);
    }

    @Test
    void alertesSelonLaSeverite() {
      var up = new EquipmentFacts(true, "UP");
      assertThat(
              ComponentStateEvaluator.equipment(
                  up, false, List.of(new AlertFact("THRESHOLD", "CRITICAL"))))
          .isEqualTo(ComponentState.PARTIAL_OUTAGE);
    }
  }

  @Nested
  class Site {
    @Test
    void agregeSesEquipements() {
      var ok = ComponentState.OPERATIONAL;
      var major = ComponentState.MAJOR_OUTAGE;
      assertThat(ComponentStateEvaluator.site(false, List.of(ok, ok))).isEqualTo(ok);
      assertThat(ComponentStateEvaluator.site(false, List.of(ok, ComponentState.DEGRADED)))
          .isEqualTo(ComponentState.DEGRADED);
      assertThat(ComponentStateEvaluator.site(false, List.of(ok, major)))
          .isEqualTo(ComponentState.PARTIAL_OUTAGE);
      assertThat(ComponentStateEvaluator.site(false, List.of(major, major))).isEqualTo(major);
    }

    @Test
    void ignoreMaintenanceEtInconnu() {
      assertThat(
              ComponentStateEvaluator.site(
                  false, List.of(ComponentState.MAINTENANCE, ComponentState.MAJOR_OUTAGE)))
          .isEqualTo(ComponentState.MAJOR_OUTAGE);
      assertThat(
              ComponentStateEvaluator.site(
                  false, List.of(ComponentState.UNKNOWN, ComponentState.OPERATIONAL)))
          .isEqualTo(ComponentState.OPERATIONAL);
      assertThat(ComponentStateEvaluator.site(false, List.of(ComponentState.MAINTENANCE)))
          .isEqualTo(ComponentState.MAINTENANCE);
      assertThat(ComponentStateEvaluator.site(false, List.of(ComponentState.UNKNOWN)))
          .isEqualTo(ComponentState.UNKNOWN);
      assertThat(ComponentStateEvaluator.site(false, List.of())).isEqualTo(ComponentState.UNKNOWN);
      assertThat(ComponentStateEvaluator.site(true, List.of(ComponentState.MAJOR_OUTAGE)))
          .isEqualTo(ComponentState.MAINTENANCE);
    }
  }

  @Nested
  class EtatGlobal {
    @Test
    void lePireComposantLEmporte_laMaintenanceNEstPasUnePanne() {
      assertThat(
              ComponentStateEvaluator.overall(
                  List.of(
                      ComponentState.OPERATIONAL,
                      ComponentState.MAINTENANCE,
                      ComponentState.PARTIAL_OUTAGE)))
          .isEqualTo(ComponentState.PARTIAL_OUTAGE);
      assertThat(
              ComponentStateEvaluator.overall(
                  List.of(ComponentState.OPERATIONAL, ComponentState.MAINTENANCE)))
          .isEqualTo(ComponentState.MAINTENANCE);
      assertThat(
              ComponentStateEvaluator.overall(
                  List.of(ComponentState.OPERATIONAL, ComponentState.UNKNOWN)))
          .isEqualTo(ComponentState.OPERATIONAL);
      assertThat(ComponentStateEvaluator.overall(List.of(ComponentState.UNKNOWN)))
          .isEqualTo(ComponentState.UNKNOWN);
      assertThat(ComponentStateEvaluator.overall(List.of())).isEqualTo(ComponentState.UNKNOWN);
    }
  }

  @Nested
  class Disponibilite {
    private final LocalDate first = LocalDate.parse("2026-11-04");

    private HourBucket bucket(String hour, long samples, double sum) {
      return new HourBucket(Instant.parse(hour), samples, sum);
    }

    @Test
    void moyenneDesMesuresParJour_joursSansDonneeInconnus() {
      var raw =
          List.of(
              bucket("2026-11-04T10:00:00Z", 60, 6000),
              bucket("2026-11-04T11:00:00Z", 60, 5400),
              bucket("2026-11-06T09:00:00Z", 10, 1000));
      var result = AvailabilityCalculator.daily(raw, List.of(), List.of(), first, 3);
      assertThat(result.days()).hasSize(3);
      assertThat(result.days().get(0)).isEqualByComparingTo("95.00");
      assertThat(result.days().get(1)).isNull();
      assertThat(result.days().get(2)).isEqualByComparingTo("100.00");
      // 11 400 + 1 000 sur 130 mesures.
      assertThat(result.overall()).isEqualByComparingTo("95.38");
    }

    @Test
    void lesHeuresEnMaintenanceSontExclues() {
      var raw =
          List.of(bucket("2026-11-04T10:00:00Z", 60, 0), bucket("2026-11-04T11:00:00Z", 60, 6000));
      var maintenance =
          List.of(
              new Window(
                  Instant.parse("2026-11-04T10:15:00Z"), Instant.parse("2026-11-04T10:45:00Z")));
      var result = AvailabilityCalculator.daily(raw, List.of(), maintenance, first, 1);
      assertThat(result.days().get(0)).isEqualByComparingTo("100.00");
    }

    @Test
    void unJourEntierementEnMaintenanceEstInconnu_jamaisCentPourCent() {
      var raw = List.of(bucket("2026-11-04T10:00:00Z", 60, 0));
      var maintenance =
          List.of(
              new Window(
                  Instant.parse("2026-11-04T00:00:00Z"), Instant.parse("2026-11-05T00:00:00Z")));
      var result = AvailabilityCalculator.daily(raw, List.of(), maintenance, first, 1);
      assertThat(result.days().get(0)).isNull();
      assertThat(result.overall()).isNull();
    }

    @Test
    void lesAgregatsHorairesCompletentLesHeuresSansMesureBrute() {
      var raw = List.of(bucket("2026-11-04T10:00:00Z", 60, 6000));
      var hourly =
          List.of(
              // Même heure que la mesure brute : la mesure brute l'emporte.
              bucket("2026-11-04T10:00:00Z", 60, 0), bucket("2026-11-04T11:00:00Z", 60, 0));
      var result = AvailabilityCalculator.daily(raw, hourly, List.of(), first, 1);
      assertThat(result.days().get(0)).isEqualByComparingTo("50.00");
    }

    @Test
    void neJamaisArrondirVersCentPourCent() {
      var raw = List.of(bucket("2026-11-04T10:00:00Z", 100_000, 9_999_990));
      var result = AvailabilityCalculator.daily(raw, List.of(), List.of(), first, 1);
      assertThat(result.days().get(0)).isEqualByComparingTo(new BigDecimal("99.99"));
    }
  }
}

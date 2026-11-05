package sn.ept.transdic1.diagnostic.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import sn.ept.transdic1.diagnostic.domain.CauseSource;

/** Fonction de score des causes probables : poids, décroissance, plancher, classement, égalités. */
class CauseScoringTests {
  private static final Instant T0 = Instant.parse("2026-11-05T14:02:00Z");

  private static CauseCandidate candidate(
      CauseSource source, double strength, Instant at, String libelle) {
    return new CauseCandidate(source, strength, at, libelle, List.of("preuve"), null, null);
  }

  @Test
  void lesPoidsSontAppliquesQuandLaPreuveCoincideAvecLOuverture() {
    assertThat(CauseScoring.score(CauseSource.AMONT_EN_ALERTE, 1.0, T0, T0)).isEqualTo(1.0);
    assertThat(CauseScoring.score(CauseSource.EVENEMENT_RECENT, 1.0, T0, T0)).isEqualTo(0.9);
    assertThat(CauseScoring.score(CauseSource.CHANGEMENT_RECENT, 1.0, T0, T0)).isEqualTo(0.8);
    assertThat(CauseScoring.score(CauseSource.SILENCE_COLLECTE, 1.0, T0, T0)).isEqualTo(0.7);
    assertThat(CauseScoring.score(CauseSource.TENDANCE_SATURATION, 1.0, T0, T0)).isEqualTo(0.6);
    assertThat(CauseScoring.score(CauseSource.ANOMALIE, 1.0, T0, T0)).isEqualTo(0.5);
    // La force de la preuve multiplie le poids.
    assertThat(CauseScoring.score(CauseSource.EVENEMENT_RECENT, 0.5, T0, T0)).isEqualTo(0.45);
  }

  @Test
  void chaqueSourceAUnPoidsEtUnPlancherDocumentes() {
    assertThat(CauseScoring.WEIGHTS).containsOnlyKeys(CauseSource.values());
    CauseScoring.WEIGHTS
        .values()
        .forEach(
            w -> {
              assertThat(w.weight()).isBetween(0.0, 1.0);
              assertThat(w.floor()).isBetween(0.0, 1.0);
            });
  }

  @Test
  void laPreuveSeDevalueAvecLEloignementTemporelDemiVieQuinzeMinutes() {
    Instant before15 = T0.minus(Duration.ofMinutes(15));
    Instant before30 = T0.minus(Duration.ofMinutes(30));
    assertThat(CauseScoring.decay(CauseSource.EVENEMENT_RECENT, before15, T0))
        .isCloseTo(0.5, within(1e-9));
    assertThat(CauseScoring.decay(CauseSource.EVENEMENT_RECENT, before30, T0))
        .isCloseTo(0.25, within(1e-9));
    assertThat(CauseScoring.score(CauseSource.EVENEMENT_RECENT, 1.0, before15, T0)).isEqualTo(0.45);
    // La distance compte dans les deux sens.
    assertThat(CauseScoring.decay(CauseSource.CHANGEMENT_RECENT, T0.plusSeconds(900), T0))
        .isCloseTo(0.5, within(1e-9));
    // Une minute avant vaut plus que cinq minutes avant.
    assertThat(CauseScoring.score(CauseSource.EVENEMENT_RECENT, 1.0, T0.minusSeconds(60), T0))
        .isGreaterThan(
            CauseScoring.score(CauseSource.EVENEMENT_RECENT, 1.0, T0.minusSeconds(300), T0));
  }

  @Test
  void unEtatDurableGardeUnPlancherEtUneTendanceNeDecroitPas() {
    Instant longAgo = T0.minus(Duration.ofHours(6));
    // Un amont en panne depuis longtemps reste une cause forte : plancher à 0,5.
    assertThat(CauseScoring.score(CauseSource.AMONT_EN_ALERTE, 1.0, longAgo, T0))
        .isCloseTo(0.5, within(0.001));
    // Une tendance de saturation se mesure en jours : pas de décroissance.
    assertThat(CauseScoring.score(CauseSource.TENDANCE_SATURATION, 1.0, longAgo, T0))
        .isEqualTo(0.6);
    // Un événement sans plancher finit par ne plus compter.
    assertThat(CauseScoring.score(CauseSource.EVENEMENT_RECENT, 1.0, longAgo, T0)).isZero();
  }

  @Test
  void leScoreResteEntreZeroEtUnEtLaForceEstBornee() {
    assertThat(CauseScoring.score(CauseSource.AMONT_EN_ALERTE, 7.0, T0, T0)).isEqualTo(1.0);
    assertThat(CauseScoring.score(CauseSource.AMONT_EN_ALERTE, -1.0, T0, T0)).isZero();
    assertThat(CauseScoring.score(CauseSource.AMONT_EN_ALERTE, Double.NaN, T0, T0)).isZero();
    // Sans instant connu, la preuve n'est pas dévaluée.
    assertThat(CauseScoring.score(CauseSource.ANOMALIE, 1.0, null, T0)).isEqualTo(0.5);
  }

  @Test
  void leClassementTrieParScoreEtGardeCinqCausesAuPlus() {
    var input = new ArrayList<CauseCandidate>();
    for (int i = 0; i < 8; i++) {
      input.add(candidate(CauseSource.EVENEMENT_RECENT, 1.0, T0.minusSeconds(60L * i), "E" + i));
    }
    input.add(candidate(CauseSource.AMONT_EN_ALERTE, 1.0, T0, "Amont"));

    var ranked = CauseScoring.rank(input, T0);

    assertThat(ranked).hasSize(CauseScoring.MAX_CAUSES);
    assertThat(ranked.get(0).candidate().libelle()).isEqualTo("Amont");
    assertThat(ranked).extracting(Scored::score).isSortedAccordingTo(Collections.reverseOrder());
    assertThat(ranked.get(1).candidate().libelle()).isEqualTo("E0");
  }

  @Test
  void lesEgalitesSontDepartageesDeFaconStableQuelQueSoitLOrdreDEntree() {
    // Même score (0,6) : la source la plus structurelle d'abord, puis la plus proche dans le
    // temps, puis l'ordre alphabétique du libellé.
    var amont = candidate(CauseSource.AMONT_EN_ALERTE, 0.6, T0, "Amont");
    var tendance = candidate(CauseSource.TENDANCE_SATURATION, 1.0, T0, "Tendance");
    var changeB = candidate(CauseSource.CHANGEMENT_RECENT, 0.75, T0, "B changement");
    var changeA = candidate(CauseSource.CHANGEMENT_RECENT, 0.75, T0, "A changement");
    var expected = List.of("Amont", "A changement", "B changement", "Tendance");

    var input = new ArrayList<>(List.of(tendance, changeB, amont, changeA));
    var random = new Random(42);
    for (int round = 0; round < 10; round++) {
      Collections.shuffle(input, random);
      assertThat(CauseScoring.rank(input, T0))
          .extracting(s -> s.candidate().libelle())
          .containsExactlyElementsOf(expected);
    }
  }

  @Test
  void aScoreEgalLaPreuveLaPlusProcheDeLOuverturePasseDevant() {
    // Même source, force ajustée pour un score arrondi identique : la proximité départage.
    var far = candidate(CauseSource.SILENCE_COLLECTE, 1.0, T0.minus(Duration.ofHours(10)), "Z");
    var near = candidate(CauseSource.SILENCE_COLLECTE, 0.5, T0.minusSeconds(1), "Y");
    // far : 0,7 × 0,5 (plancher) = 0,35 ; near : 0,7 × 0,5 × ~1 = 0,35.
    var ranked = CauseScoring.rank(List.of(far, near), T0);
    assertThat(ranked).extracting(Scored::score).containsExactly(0.35, 0.35);
    assertThat(ranked).extracting(s -> s.candidate().libelle()).containsExactly("Y", "Z");
  }

  @Test
  void uneCauseDeScoreNulNestPasProposee() {
    var dead = candidate(CauseSource.EVENEMENT_RECENT, 1.0, T0.minus(Duration.ofDays(2)), "Vieux");
    assertThat(CauseScoring.rank(List.of(dead), T0)).isEmpty();
    assertThat(CauseScoring.rank(List.of(), T0)).isEmpty();
  }
}

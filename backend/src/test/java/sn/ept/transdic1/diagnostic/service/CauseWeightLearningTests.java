package sn.ept.transdic1.diagnostic.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumMap;
import java.util.Map;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sn.ept.transdic1.diagnostic.domain.CauseSource;

/**
 * Poids du diagnostic appris (M12) : ce que les confirmations changent, à quelle vitesse, et ce
 * qu'elles ne changent pas.
 */
class CauseWeightLearningTests {
  private static final double SMOOTHING = CauseWeightLearning.DEFAULT_SMOOTHING;

  private static Map<CauseSource, CauseWeightLearning.Tally> tally(
      CauseSource source, int proposed, int chosen) {
    var tallies = new EnumMap<CauseSource, CauseWeightLearning.Tally>(CauseSource.class);
    tallies.put(source, new CauseWeightLearning.Tally(proposed, chosen));
    return tallies;
  }

  @Test
  @DisplayName("Sans aucune confirmation, les poids sont exactement ceux fixés à la main")
  void withoutAnyConfirmationTheweightsAreExactlyTheHandSetOnes() {
    var weights = CauseWeightLearning.weights(Map.of(), SMOOTHING);

    assertThat(weights).isEqualTo(CauseScoring.WEIGHTS);
    assertThat(CauseWeightLearning.weights(null, SMOOTHING)).isEqualTo(CauseScoring.WEIGHTS);
  }

  @Test
  @DisplayName("Ce que M12 apporte : une source que l'opérateur n'a jamais retenue perd du poids")
  void whatM12addsAsourceNeverChosenLosesWeight() {
    // L'anomalie est proposée cinquante fois et jamais retenue : l'opinion initiale de 0,5 ne
    // tient pas devant cinquante refus.
    double before = CauseScoring.WEIGHTS.get(CauseSource.ANOMALIE).weight();

    var weights = CauseWeightLearning.weights(tally(CauseSource.ANOMALIE, 50, 0), SMOOTHING);

    assertThat(before).isEqualTo(0.5);
    assertThat(weights.get(CauseSource.ANOMALIE).weight()).isLessThan(0.1);
  }

  @Test
  @DisplayName("Une source presque toujours retenue monte, sans dépasser 1")
  void asourceAlmostAlwaysChosenRisesWithoutExceedingOne() {
    var weights =
        CauseWeightLearning.weights(tally(CauseSource.CHANGEMENT_RECENT, 100, 100), SMOOTHING);

    assertThat(weights.get(CauseSource.CHANGEMENT_RECENT).weight())
        .isGreaterThan(0.95)
        .isLessThanOrEqualTo(1.0);
  }

  @Test
  @DisplayName("Trois confirmations ne retournent pas l'opinion initiale")
  void threeConfirmationsDoNotOverturnTheInitialOpinion() {
    // Trois refus sur une source dont le poids à la main vaut 1 : elle descend un peu, pas
    // beaucoup. C'est tout l'intérêt du lissage — pas de bascule brutale.
    var weights = CauseWeightLearning.weights(tally(CauseSource.AMONT_EN_ALERTE, 3, 0), SMOOTHING);

    assertThat(weights.get(CauseSource.AMONT_EN_ALERTE).weight())
        .isCloseTo(0.77, Offset.offset(0.01));
  }

  @Test
  @DisplayName("Le poids glisse progressivement, sans seuil ni marche d'escalier")
  void theweightSlidesProgressivelyWithoutAthreshold() {
    double prior = CauseScoring.WEIGHTS.get(CauseSource.SILENCE_COLLECTE).weight();
    double[] previous = {prior};

    for (int proposed : new int[] {1, 5, 10, 25, 50, 100, 200}) {
      double weight =
          CauseWeightLearning.weights(tally(CauseSource.SILENCE_COLLECTE, proposed, 0), SMOOTHING)
              .get(CauseSource.SILENCE_COLLECTE)
              .weight();
      // Chaque pas descend, et aucun ne descend d'un coup : personne ne verra le diagnostic
      // changer de comportement du jour au lendemain sans comprendre pourquoi.
      assertThat(weight).isLessThan(previous[0]);
      previous[0] = weight;
    }
    assertThat(previous[0]).isLessThan(0.1);
  }

  @Test
  @DisplayName("Les refus comptent : être souvent proposé ne suffit pas")
  void therefusalsCountBeingOftenProposedIsNotEnough() {
    // Même nombre de fois retenue, mais proposée deux fois plus souvent : le poids doit baisser.
    var often =
        CauseWeightLearning.weights(tally(CauseSource.EVENEMENT_RECENT, 100, 50), SMOOTHING);
    var rarely =
        CauseWeightLearning.weights(tally(CauseSource.EVENEMENT_RECENT, 50, 50), SMOOTHING);

    assertThat(often.get(CauseSource.EVENEMENT_RECENT).weight())
        .isLessThan(rarely.get(CauseSource.EVENEMENT_RECENT).weight());
  }

  @Test
  @DisplayName("Le plancher de décroissance n'est pas appris")
  void thedecayFloorIsNotLearned() {
    var weights =
        CauseWeightLearning.weights(tally(CauseSource.TENDANCE_SATURATION, 80, 2), SMOOTHING);

    // Le plancher dit la durabilité d'une preuve — une tendance de saturation ne vieillit pas.
    // Les confirmations ne mesurent pas cela, donc elles ne le touchent pas.
    assertThat(weights.get(CauseSource.TENDANCE_SATURATION).floor())
        .isEqualTo(CauseScoring.WEIGHTS.get(CauseSource.TENDANCE_SATURATION).floor());
  }

  @Test
  @DisplayName("Une source sans confirmation garde son poids même si les autres en ont")
  void asourceWithoutConfirmationKeepsItsWeight() {
    var weights = CauseWeightLearning.weights(tally(CauseSource.ANOMALIE, 50, 0), SMOOTHING);

    assertThat(weights.get(CauseSource.AMONT_EN_ALERTE))
        .isEqualTo(CauseScoring.WEIGHTS.get(CauseSource.AMONT_EN_ALERTE));
  }

  @Test
  @DisplayName("On n'annonce des poids appris qu'au-delà de vingt confirmations")
  void learnedWeightsAreOnlyAnnouncedBeyondTwentyConfirmations() {
    assertThat(CauseWeightLearning.usable(tally(CauseSource.ANOMALIE, 19, 5), 20)).isFalse();
    assertThat(CauseWeightLearning.usable(tally(CauseSource.ANOMALIE, 20, 5), 20)).isTrue();
    assertThat(CauseWeightLearning.usable(Map.of(), 20)).isFalse();
    assertThat(CauseWeightLearning.confirmations(tally(CauseSource.ANOMALIE, 42, 5))).isEqualTo(42);
  }

  @Test
  @DisplayName("Un poids reste une part : jamais au-dessus de 1, jamais nul")
  void aweightStaysAshareNeverAboveOneNeverZero() {
    var all = CauseWeightLearning.weights(tally(CauseSource.ANOMALIE, 10_000, 0), SMOOTHING);

    // Un plancher à 0,05 : une source qu'on n'a jamais retenue reste proposable en dernier
    // recours, parce que « jamais vu » n'est pas « impossible ».
    assertThat(all.get(CauseSource.ANOMALIE).weight()).isEqualTo(0.05);
    assertThat(
            CauseWeightLearning.weights(tally(CauseSource.ANOMALIE, 10, 10), SMOOTHING)
                .get(CauseSource.ANOMALIE)
                .weight())
        .isLessThanOrEqualTo(1.0);
  }

  @Test
  @DisplayName("Un lissage absurde est ramené dans le raisonnable")
  void anabsurdSmoothingIsBroughtBackToReason() {
    assertThat(
            CauseWeightLearning.weights(tally(CauseSource.ANOMALIE, 10, 10), -5)
                .get(CauseSource.ANOMALIE)
                .weight())
        .isFinite();
    assertThat(
            CauseWeightLearning.weights(tally(CauseSource.ANOMALIE, 10, 10), 0)
                .get(CauseSource.ANOMALIE)
                .weight())
        .isGreaterThan(0.9);
  }
}

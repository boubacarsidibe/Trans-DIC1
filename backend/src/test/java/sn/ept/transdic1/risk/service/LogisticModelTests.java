package sn.ept.transdic1.risk.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Régression logistique écrite à la main : elle apprend, elle se mesure, elle se mélange. */
class LogisticModelTests {

  @Test
  @DisplayName("Apprend qu'une variable annonce la panne et qu'une autre n'y est pour rien")
  void learnsTheSignalAndIgnoresTheNoise() {
    Random random = new Random(42);
    List<double[]> x = new ArrayList<>();
    List<Integer> y = new ArrayList<>();
    for (int i = 0; i < 2000; i++) {
      double disk = random.nextDouble();
      double noise = random.nextDouble();
      double p = LogisticModel.sigmoid(-5 + 6 * disk);
      x.add(new double[] {disk, noise});
      y.add(random.nextDouble() < p ? 1 : 0);
    }
    int[] labels = y.stream().mapToInt(Integer::intValue).toArray();
    double[] w = LogisticModel.train(x, labels, 0.0001, 0.5, 5000);
    assertThat(w[1]).isGreaterThan(4); // vrai poids : 6
    assertThat(Math.abs(w[2])).isLessThan(0.8); // vrai poids : 0
    double[] scores = x.stream().mapToDouble(v -> LogisticModel.probability(w, v)).toArray();
    assertThat(LogisticModel.auc(scores, labels)).isGreaterThan(0.75);
  }

  @Test
  @DisplayName("Même historique, mêmes poids, au bit près")
  void deterministic() {
    List<double[]> x = List.of(new double[] {0, 1}, new double[] {1, 0}, new double[] {1, 1});
    int[] y = {0, 1, 1};
    assertThat(LogisticModel.train(x, y, 0.01, 0.5, 500))
        .containsExactly(LogisticModel.train(x, y, 0.01, 0.5, 500));
  }

  @Test
  @DisplayName("AUC : 1 pour un classement parfait, 0,5 pour des ex aequo, nulle sans panne")
  void auc() {
    assertThat(LogisticModel.auc(new double[] {0.1, 0.2, 0.8, 0.9}, new int[] {0, 0, 1, 1}))
        .isEqualTo(1.0);
    assertThat(LogisticModel.auc(new double[] {0.5, 0.5}, new int[] {0, 1})).isEqualTo(0.5);
    assertThat(LogisticModel.auc(new double[] {0.1, 0.2}, new int[] {0, 0})).isNull();
  }

  @Test
  @DisplayName("Sans panne observée, les poids initiaux ; avec beaucoup, l'expérience l'emporte")
  void blendShrinksTowardsThePrior() {
    double[] learned = {-2, 4};
    double[] prior = {-4, 1};
    assertThat(LogisticModel.blend(learned, prior, 0, 20)).containsExactly(-4, 1);
    assertThat(LogisticModel.blend(learned, prior, 20, 20)).containsExactly(-3, 2.5);
    assertThat(LogisticModel.blend(learned, prior, 2000, 20)[1]).isCloseTo(4, within(0.05));
  }

  @Test
  @DisplayName("Un équipement sans aucun signe a un risque d'environ 2 % avec les poids initiaux")
  void priorBaseline() {
    double p =
        LogisticModel.probability(
            RiskFeatures.priorWeights(), RiskFeatures.vector(RiskFeatures.Facts.empty()));
    assertThat(p).isBetween(0.01, 0.03);
    double loaded =
        LogisticModel.probability(
            RiskFeatures.priorWeights(),
            RiskFeatures.vector(new RiskFeatures.Facts(2, 1, 1, false, 60.0, 70.0, 96.0, 1, 0)));
    assertThat(loaded).isGreaterThan(0.5);
  }

  @Test
  @DisplayName("Un poste au repos, sans alerte, reste faible avec les poids initiaux")
  void anIdleMachineStaysLowWithThePrior() {
    // Le cas montré au jury : disque à 86 %, mémoire à 77 %, processeur à 65 %, rien d'autre.
    // L'occupation brute lui donnait 41 % ; seule la zone de saturation compte désormais.
    double p =
        LogisticModel.probability(
            RiskFeatures.priorWeights(),
            RiskFeatures.vector(new RiskFeatures.Facts(0, 0, 0, false, 65.0, 77.0, 86.0, 0, 0)));
    assertThat(p).isLessThan(0.05);
    double[] x =
        RiskFeatures.vector(new RiskFeatures.Facts(0, 0, 0, false, 79.0, 80.0, 100.0, 0, 0));
    assertThat(x[4]).isZero();
    assertThat(x[5]).isZero();
    assertThat(x[6]).isEqualTo(1.0);
  }
}

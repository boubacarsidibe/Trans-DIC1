package sn.ept.transdic1.alerting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Forêt d'isolement (M2) : ce qu'elle voit, ce qu'elle ne voit pas, et sa reproductibilité. Les
 * jeux sont construits à la main, avec un générateur à graine fixe : un test de détection qui
 * change de verdict d'une exécution à l'autre ne vaut rien.
 */
class IsolationForestTests {
  private static final long SEED = 20_261_123L;

  /** Nuage ordinaire : trois indicateurs qui varient peu autour de leur valeur habituelle. */
  private static double[][] ordinary(int rows) {
    var random = new Random(7L);
    var data = new double[rows][3];
    for (int i = 0; i < rows; i++) {
      data[i][0] = 40 + random.nextGaussian() * 5; // processeur, %
      data[i][1] = 60 + random.nextGaussian() * 4; // mémoire, %
      data[i][2] = 12 + random.nextGaussian() * 2; // latence, ms
    }
    return data;
  }

  @Test
  @DisplayName("Un point au milieu du nuage a un score bas, un point à l'écart un score haut")
  void apointInTheMiddleScoresLowAndAnOutlierScoresHigh() {
    var forest = IsolationForest.fit(ordinary(500), IsolationForest.DEFAULT_TREES, 256, SEED);

    double ordinary = forest.score(new double[] {40, 60, 12});
    double outlier = forest.score(new double[] {95, 95, 80});

    assertThat(ordinary).isLessThan(0.55);
    assertThat(outlier).isGreaterThan(0.65);
    assertThat(outlier).isGreaterThan(ordinary);
  }

  @Test
  @DisplayName("Ce que la méthode apporte : anormal à plusieurs, ordinaire mesure par mesure")
  void whatTheMethodAddsAbnormalTogetherOrdinaryOneByOne() {
    // Nuage où processeur et latence vont de pair : quand l'un monte, l'autre monte.
    var random = new Random(11L);
    var data = new double[600][2];
    for (int i = 0; i < data.length; i++) {
      double load = 30 + random.nextDouble() * 40; // 30 à 70
      data[i][0] = load;
      data[i][1] = load / 3 + random.nextGaussian(); // latence liée à la charge
    }
    var forest = IsolationForest.fit(data, IsolationForest.DEFAULT_TREES, 256, SEED);

    // Processeur à 35 % : banal. Latence à 22 ms : banale aussi (on en voit jusqu'à 24).
    // Mais les deux ensemble ne se rencontrent jamais : une machine peu chargée qui répond
    // lentement, c'est exactement ce qu'aucun seuil pris à part ne montre.
    double together = forest.score(new double[] {35, 22});
    double consistent = forest.score(new double[] {35, 12});

    assertThat(together).isGreaterThan(consistent);
  }

  @Test
  @DisplayName("Deux calculs sur les mêmes données donnent le même score")
  void twocomputationsOnTheSameDataGiveTheSameScore() {
    var data = ordinary(300);
    double first = IsolationForest.fit(data, 50, 128, SEED).score(new double[] {90, 90, 60});
    double second = IsolationForest.fit(data, 50, 128, SEED).score(new double[] {90, 90, 60});

    // Sans graine fixe, l'alerte apparaîtrait et disparaîtrait d'un passage à l'autre.
    assertThat(first).isEqualTo(second);
  }

  @Test
  @DisplayName("Le score reste entre 0 et 1")
  void thescoreStaysBetweenZeroAndOne() {
    var forest = IsolationForest.fit(ordinary(200), 30, 64, SEED);

    for (double[] point :
        new double[][] {{40, 60, 12}, {0, 0, 0}, {1_000, 1_000, 1_000}, {-50, 60, 12}}) {
      assertThat(forest.score(point)).isBetween(0.0, 1.0);
    }
  }

  @Test
  @DisplayName("Une série parfaitement plate ne rend personne anormal")
  void aperfectlyFlatSeriesMakesNobodyAbnormal() {
    var data = new double[200][2];
    for (double[] row : data) {
      row[0] = 50;
      row[1] = 10;
    }

    var forest = IsolationForest.fit(data, 30, 64, SEED);

    // Rien à découper : tous les points s'isolent à la même profondeur.
    assertThat(forest.score(new double[] {50, 10})).isLessThan(0.75);
  }

  @Test
  @DisplayName("Un point mal formé est refusé plutôt que mal jugé")
  void amalformedPointIsRefusedRatherThanMisjudged() {
    var forest = IsolationForest.fit(ordinary(100), 20, 64, SEED);

    assertThatThrownBy(() -> forest.score(new double[] {1, 2}))
        .hasMessageContaining("3 indicateurs attendus");
    assertThatThrownBy(() -> forest.score(null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> IsolationForest.fit(new double[0][0], 10, 10, SEED))
        .hasMessageContaining("aucune donnée");
  }

  @Test
  @DisplayName("La profondeur moyenne de référence suit la formule publiée")
  void thereferenceAveragePathFollowsThePublishedFormula() {
    assertThat(IsolationForest.averagePath(1)).isZero();
    assertThat(IsolationForest.averagePath(2)).isEqualTo(1);
    // c(256) ≈ 10,24 : la valeur de référence de l'article d'origine.
    assertThat(IsolationForest.averagePath(256))
        .isCloseTo(10.24, org.assertj.core.data.Offset.offset(0.05));
  }
}

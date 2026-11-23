package sn.ept.transdic1.diagnostic.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Random;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Corrélation de rang (M11) : ce qu'elle voit, ce qu'une comparaison avant/après rate, et ce
 * qu'elle refuse de juger. Le bruit vient d'un générateur à graine fixe.
 */
class RankCorrelationTests {
  private static final int MIN_POINTS = 10;

  private static double[] series(int n, java.util.function.IntToDoubleFunction value) {
    var values = new double[n];
    for (int i = 0; i < n; i++) {
      values[i] = value.applyAsDouble(i);
    }
    return values;
  }

  @Test
  @DisplayName("Deux séries qui montent ensemble donnent 1, en sens inverse -1")
  void twoseriesRisingTogetherGiveOneAndOppositeMinusOne() {
    var rising = series(20, i -> i);
    var falling = series(20, i -> -i);

    assertThat(RankCorrelation.between(rising, rising, MIN_POINTS).rho()).isEqualTo(1.0);
    assertThat(RankCorrelation.between(rising, falling, MIN_POINTS).rho()).isEqualTo(-1.0);
  }

  @Test
  @DisplayName("Ce que la méthode apporte : une relation courbe, qu'une droite raterait")
  void whatTheMethodAddsAcurvedRelationAlineWouldMiss() {
    // Latence contre charge : plate tant qu'il reste de la marge, puis elle s'envole. La relation
    // est montante mais pas droite ; Spearman ne regarde que l'ordre et la voit entièrement.
    var load = series(30, i -> 40 + i * 2);
    var latency = series(30, i -> Math.pow(1.3, i));

    var result = RankCorrelation.between(load, latency, MIN_POINTS);

    assertThat(result.rho()).isEqualTo(1.0);
    assertThat(result.pValue()).isLessThan(0.001);
  }

  @Test
  @DisplayName("Ce que la comparaison avant/après rate : varier ensemble sans changer de niveau")
  void whatABeforeAfterComparisonMissesVaryingTogetherWithoutChangingLevel() {
    // Les deux séries oscillent de la même façon : moyenne avant et moyenne après identiques, donc
    // MetricMovement ne voit rien, et pourtant le lien est total.
    var random = new Random(11L);
    var first = series(40, i -> 50 + random.nextGaussian() * 10);
    var second = series(40, i -> first[i] * 0.8 + 3);

    assertThat(MetricMovement.changePercent(mean(first, 0, 20), mean(first, 20, 40)))
        .isLessThan(MetricMovement.MIN_CHANGE_PERCENT);
    assertThat(RankCorrelation.between(first, second, MIN_POINTS).rho()).isEqualTo(1.0);
  }

  @Test
  @DisplayName("Une seule valeur extrême ne fabrique pas une corrélation")
  void asingleExtremeValueDoesNotManufactureAcorrelation() {
    var random = new Random(7L);
    var first = series(40, i -> random.nextGaussian());
    var second = series(40, i -> random.nextGaussian());
    // La pointe que toute alerte comporte : elle tirerait un Pearson vers 1.
    first[39] = 1_000;
    second[39] = 1_000;

    double spearman = RankCorrelation.between(first, second, MIN_POINTS).rho();
    double linear = RankCorrelation.pearson(first, second);

    assertThat(linear).isGreaterThan(0.9);
    assertThat(spearman).isLessThan(0.4);
  }

  @Test
  @DisplayName("Deux séries sans lien ne sont pas déclarées liées")
  void twounrelatedSeriesAreNotDeclaredRelated() {
    var random = new Random(23L);
    var first = series(60, i -> random.nextGaussian());
    var second = series(60, i -> random.nextGaussian());

    var result = RankCorrelation.between(first, second, MIN_POINTS);

    assertThat(Math.abs(result.rho())).isLessThan(0.35);
    assertThat(result.pValue()).isGreaterThan(0.05);
  }

  @Test
  @DisplayName("Les égalités reçoivent un rang moyen : la formule abrégée serait fausse")
  void tiesGetAnaverageRankTheShortFormulaWouldBeWrong() {
    // Trois valeurs égales en tête : rangs 1, 2, 3 → 2 pour chacune.
    var ranks = RankCorrelation.ranks(new double[] {5, 5, 5, 9, 12});

    assertThat(ranks).containsExactly(2.0, 2.0, 2.0, 4.0, 5.0);
  }

  @Test
  @DisplayName("Un indicateur figé n'est corrélé à rien, pas même à zéro")
  void afrozenMetricIsCorrelatedToNothingNotEvenZero() {
    var flat = series(30, i -> 100);
    var moving = series(30, i -> i);

    // Une droite plate n'a pas d'ordre : répondre « zéro » laisserait croire qu'on a mesuré
    // l'absence de lien, alors qu'on n'a rien pu mesurer.
    assertThat(RankCorrelation.ranks(flat)).isNull();
    assertThat(RankCorrelation.between(flat, moving, MIN_POINTS)).isNull();
    assertThat(RankCorrelation.between(flat, flat, MIN_POINTS)).isNull();
  }

  @Test
  @DisplayName("Trop peu d'instants : on ne conclut pas")
  void toofewInstantsNoconclusion() {
    assertThat(RankCorrelation.between(series(6, i -> i), series(6, i -> i), MIN_POINTS)).isNull();
    assertThat(RankCorrelation.between(series(3, i -> i), series(3, i -> i), 2)).isNull();
    // Dix instants exactement, c'est assez.
    assertThat(RankCorrelation.between(series(10, i -> i), series(10, i -> i), MIN_POINTS))
        .isNotNull();
  }

  @Test
  @DisplayName("Des séries mal formées sont refusées plutôt que mal jugées")
  void malformedSeriesAreRefusedRatherThanMisjudged() {
    assertThatThrownBy(
            () -> RankCorrelation.between(series(10, i -> i), series(9, i -> i), MIN_POINTS))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> RankCorrelation.between(null, series(10, i -> i), MIN_POINTS))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("La probabilité du hasard suit la table : une corrélation faible sur peu de points")
  void thechanceProbabilityFollowsTheTable() {
    // rho = 0,5 sur 20 points : non concluant au seuil usuel. Sur 100 points, il ne l'est plus.
    assertThat(RankCorrelation.pValue(0.5, 20)).isGreaterThan(0.02);
    assertThat(RankCorrelation.pValue(0.5, 100)).isLessThan(0.001);
    // Une corrélation nulle, c'est le hasard par définition (à l'arrondi de l'approximation).
    assertThat(RankCorrelation.pValue(0, 50)).isCloseTo(1.0, Offset.offset(1e-6));
  }

  @Test
  @DisplayName("L'approximation de la loi normale est juste aux valeurs connues")
  void thenormalApproximationIsRightAtTheKnownValues() {
    assertThat(RankCorrelation.normalTail(0)).isCloseTo(0.5, Offset.offset(1e-6));
    assertThat(RankCorrelation.normalTail(1.96)).isCloseTo(0.025, Offset.offset(1e-4));
    assertThat(RankCorrelation.normalTail(2.576)).isCloseTo(0.005, Offset.offset(1e-4));
    assertThat(RankCorrelation.normalTail(-1.96)).isCloseTo(0.975, Offset.offset(1e-4));
  }

  @Test
  @DisplayName("Le même calcul deux fois donne le même résultat")
  void thesameComputationTwiceGivesTheSameResult() {
    var random = new Random(31L);
    var first = series(50, i -> random.nextGaussian());
    var second = series(50, i -> first[i] + random.nextGaussian() * 0.5);

    assertThat(RankCorrelation.between(first, second, MIN_POINTS))
        .isEqualTo(RankCorrelation.between(first, second, MIN_POINTS));
  }

  private static double mean(double[] values, int from, int to) {
    double total = 0;
    for (int i = from; i < to; i++) {
      total += values[i];
    }
    return total / (to - from);
  }
}

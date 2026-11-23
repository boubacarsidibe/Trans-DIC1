package sn.ept.transdic1.alerting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Rupture de niveau (M5) : ce que le CUSUM voit, et surtout ce qu'il doit refuser de voir. Les
 * séries sont écrites à la main, le bruit tiré d'un générateur à graine fixe : une détection qui
 * change d'avis d'une exécution à l'autre ne vaut rien.
 */
class LevelShiftTests {
  private static final LevelShift.Settings SETTINGS = LevelShift.Settings.defaults();

  /**
   * Une série de {@code hours} heures autour de {@code level}, avec du bruit d'écart {@code sd}.
   */
  private static double[] steady(int hours, double level, double sd, long seed) {
    var random = new Random(seed);
    var series = new double[hours];
    for (int i = 0; i < hours; i++) {
      series[i] = level + random.nextGaussian() * sd;
    }
    return series;
  }

  @Test
  @DisplayName("Un lien qui tombe de 1 Gb/s à 100 Mb/s est vu, et daté")
  void alinkFallingFromAGigabitIsSeenAndDated() {
    var series = new double[240];
    for (int i = 0; i < series.length; i++) {
      series[i] = i < 150 ? 1_000 : 100;
    }

    var result = LevelShift.detect(series, SETTINGS);

    assertThat(result).isNotNull();
    // Le CUSUM revient au dernier instant où la somme valait zéro : la rupture est datée à l'heure.
    assertThat(result.onset()).isEqualTo(150);
    assertThat(result.before()).isEqualTo(1_000);
    assertThat(result.after()).isEqualTo(100);
    assertThat(result.rising()).isFalse();
    assertThat(result.magnitude()).isEqualTo(-900);
    assertThat(result.relative()).isCloseTo(-0.9, org.assertj.core.data.Offset.offset(0.001));
  }

  @Test
  @DisplayName("Ce que la méthode apporte : une fuite mémoire qu'aucun seuil ne franchit")
  void whatTheMethodAddsaMemoryLeakNoThresholdCrosses() {
    // Mémoire à 40 %, puis un demi-point gagné par heure à partir de la 120ᵉ. Aucune heure ne
    // ressemble à la précédente de plus d'un demi-point : un seuil, ou une comparaison à
    // l'habitude du même créneau, ne verraient jamais rien. La somme cumulée, si.
    var random = new Random(31L);
    var series = new double[240];
    for (int i = 0; i < series.length; i++) {
      series[i] = 40 + random.nextGaussian() * 2 + (i > 120 ? 0.3 * (i - 120) : 0);
    }

    var result = LevelShift.detect(series, SETTINGS);

    assertThat(result).isNotNull();
    assertThat(result.rising()).isTrue();
    // Le début de la dérive est retrouvé à quelques heures près, pas l'heure du signal.
    assertThat(result.onset()).isBetween(115, 135);
    assertThat(result.before()).isCloseTo(40, org.assertj.core.data.Offset.offset(2.0));
    assertThat(result.after()).isGreaterThan(50);
  }

  @Test
  @DisplayName("Du bruit seul n'est pas une rupture")
  void noiseAloneIsNotaShift() {
    assertThat(LevelShift.detect(steady(240, 45, 6, 7L), SETTINGS)).isNull();
    assertThat(LevelShift.detect(steady(400, 1_200, 150, 8L), SETTINGS)).isNull();
  }

  @Test
  @DisplayName("Une série rigoureusement plate ne signale rien")
  void aperfectlyFlatSeriesSignalsNothing() {
    var series = new double[240];
    java.util.Arrays.fill(series, 70);

    assertThat(LevelShift.detect(series, SETTINGS)).isNull();
  }

  @Test
  @DisplayName("Un pic de trois heures n'est pas une rupture de niveau")
  void athreeHourSpikeIsNotaLevelShift() {
    var series = steady(240, 50, 1, 13L);
    for (int i = 120; i < 123; i++) {
      series[i] = 90;
    }

    // La somme cumulée s'emballe bien, mais le niveau depuis n'a pas changé : c'est l'affaire de
    // la détection par point, pas de celle-ci.
    assertThat(LevelShift.detect(series, SETTINGS)).isNull();
  }

  @Test
  @DisplayName("Un niveau déjà revenu ne reste pas signalé")
  void alevelAlreadyBackIsNotStillSignalled() {
    var series = new double[240];
    for (int i = 0; i < series.length; i++) {
      series[i] = i >= 100 && i < 200 ? 80 : 50;
    }

    assertThat(LevelShift.detect(series, SETTINGS)).isNull();
  }

  @Test
  @DisplayName("Un décalage trop faible pour être affirmé est écarté")
  void ashiftTooSmallToAssertIsDiscarded() {
    var random = new Random(17L);
    var series = new double[240];
    for (int i = 0; i < series.length; i++) {
      series[i] = (i < 140 ? 50 : 52) + random.nextGaussian() * 2;
    }

    // Deux points sur un bruit d'écart 2 : la somme cumulée le voit, mais l'affirmer serait
    // promettre plus que la mesure ne contient.
    assertThat(LevelShift.detect(series, SETTINGS)).isNull();
  }

  @Test
  @DisplayName("Une rupture de la veille attend d'avoir duré")
  void ashiftFromYesterdayWaitsUntilItHasLasted() {
    var justNow = new double[240];
    var longEnough = new double[240];
    for (int i = 0; i < 240; i++) {
      justNow[i] = i < 237 ? 20 : 60; // trois heures seulement
      longEnough[i] = i < 230 ? 20 : 60; // dix heures
    }

    assertThat(LevelShift.detect(justNow, SETTINGS)).isNull();
    assertThat(LevelShift.detect(longEnough, SETTINGS)).isNotNull();
  }

  @Test
  @DisplayName("Une série trop courte est refusée plutôt que mal jugée")
  void aseriesTooShortIsRefusedRatherThanMisjudged() {
    assertThat(LevelShift.detect(steady(20, 50, 1, 3L), SETTINGS)).isNull();
    assertThat(LevelShift.detect(new double[0], SETTINGS)).isNull();
    assertThatThrownBy(() -> LevelShift.detect(null, SETTINGS))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> LevelShift.detect(new double[240], null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Deux calculs sur la même série donnent le même verdict")
  void twocomputationsOnTheSameSeriesGiveTheSameVerdict() {
    var series = steady(240, 60, 3, 23L);
    for (int i = 160; i < series.length; i++) {
      series[i] += 20;
    }

    var first = LevelShift.detect(series, SETTINGS);
    var second = LevelShift.detect(series, SETTINGS);

    assertThat(first).isEqualTo(second);
    assertThat(first).isNotNull();
  }

  @Test
  @DisplayName("Un compteur figé qui se met à bouger est un fait, pas du bruit")
  void afrozenCounterThatStartsMovingIsaFactNotNoise() {
    // Zéro erreur pendant cent cinquante heures, puis cinquante par heure : aucun écart-type à
    // calculer, et pourtant c'est exactement ce qu'on veut savoir.
    var series = new double[240];
    for (int i = 150; i < series.length; i++) {
      series[i] = 50;
    }

    var result = LevelShift.detect(series, SETTINGS);

    assertThat(result).isNotNull();
    assertThat(result.before()).isZero();
    assertThat(result.after()).isEqualTo(50);
    assertThat(result.relative()).isZero(); // indéfinie à partir de zéro, et dite telle
  }
}

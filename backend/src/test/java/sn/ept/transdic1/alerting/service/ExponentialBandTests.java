package sn.ept.transdic1.alerting.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Bande de tolérance exponentielle (M6) : ce qu'elle accepte, ce qu'elle signale, et surtout
 * qu'elle ne se laisse pas élargir par ce qu'elle doit signaler.
 */
class ExponentialBandTests {
  private static final double ALPHA = ExponentialBand.DEFAULT_ALPHA;
  private static final double WIDTH = ExponentialBand.DEFAULT_WIDTH;

  /** Alimente la bande avec {@code count} mesures autour de {@code level}. */
  private static ExponentialBand.State feed(
      ExponentialBand.State state, int count, double level, double noise, long seed) {
    var random = new Random(seed);
    var current = state;
    for (int i = 0; i < count; i++) {
      double value = level + random.nextGaussian() * noise;
      current =
          current == null
              ? ExponentialBand.start(value)
              : ExponentialBand.update(current, value, ALPHA, WIDTH);
    }
    return current;
  }

  @Test
  @DisplayName("La bande se construit en quelques dizaines de mesures, pas en six semaines")
  void thebandIsBuiltInTensOfSamplesNotSixWeeks() {
    var state = feed(null, 25, 100, 8, 3L);

    assertThat(state.ready()).isTrue();
    assertThat(state.level()).isCloseTo(100, org.assertj.core.data.Offset.offset(12.0));
    // Avant vingt mesures, elle ne juge personne.
    assertThat(feed(null, 19, 100, 8, 3L).ready()).isFalse();
    assertThat(feed(null, 19, 100, 8, 3L).outside(1_000, WIDTH)).isFalse();
  }

  @Test
  @DisplayName("Du trafic ordinaire reste dans la bande")
  void ordinaryTrafficStaysInTheBand() {
    var state = feed(null, 200, 500, 40, 7L);
    var random = new Random(11L);

    for (int i = 0; i < 200; i++) {
      double value = 500 + random.nextGaussian() * 40;
      assertThat(state.outside(value, WIDTH)).isFalse();
      state = ExponentialBand.update(state, value, ALPHA, WIDTH);
    }
  }

  @Test
  @DisplayName("Une pointe franche sort de la bande")
  void aclearSpikeLeavesTheBand() {
    var state = feed(null, 200, 500, 40, 13L);

    assertThat(state.outside(5_000, WIDTH)).isTrue();
    assertThat(state.excess(5_000, WIDTH)).isGreaterThan(1);
    // Et en dessous aussi : un port qui s'effondre est un fait.
    assertThat(state.outside(0, WIDTH)).isTrue();
  }

  @Test
  @DisplayName("Ce qui fait tenir la méthode : une pointe n'élargit pas la bande")
  void whatMakesTheMethodHoldAspikeDoesNotWidenTheBand() {
    var state = feed(null, 300, 500, 40, 17L);
    double before = state.spread(WIDTH);

    // Dix pointes énormes de suite. Sans winsorisation, la bande les avalerait et finirait par
    // tout accepter : la détection s'éteindrait d'elle-même, en silence.
    var after = state;
    for (int i = 0; i < 10; i++) {
      assertThat(after.outside(50_000, WIDTH)).isTrue();
      after = ExponentialBand.update(after, 50_000, ALPHA, WIDTH);
    }

    // Elle s'élargit un peu — dix pointes de suite ne sont pas rien — mais d'un facteur deux et
    // non huit, et surtout elle signale toujours la pointe.
    assertThat(after.spread(WIDTH)).isLessThan(before * 2.5);
    assertThat(after.outside(50_000, WIDTH)).isTrue();
  }

  @Test
  @DisplayName("Sans winsorisation, la bande se serait élargie dix fois plus")
  void withoutWinsorisationTheBandWouldHaveWidenedTenTimesMore() {
    var state = feed(null, 300, 500, 40, 19L);
    double reference = state.spread(WIDTH);

    var winsorised = state;
    var naive = state;
    for (int i = 0; i < 10; i++) {
      winsorised = ExponentialBand.update(winsorised, 50_000, ALPHA, WIDTH);
      // Ce qu'aurait donné un lissage qui prend la valeur telle quelle.
      double level = naive.level() + ALPHA * (50_000 - naive.level());
      double deviation =
          naive.deviation() + ALPHA * (Math.abs(50_000 - naive.level()) - naive.deviation());
      naive = new ExponentialBand.State(level, deviation, naive.samples() + 1);
    }

    assertThat(naive.spread(WIDTH)).isGreaterThan(winsorised.spread(WIDTH) * 10);
    // Le chiffre qui compte : le lissage naïf accepte désormais la pointe qu'il devait signaler.
    assertThat(naive.outside(50_000, WIDTH)).isFalse();
    assertThat(winsorised.outside(50_000, WIDTH)).isTrue();
  }

  @Test
  @DisplayName("La bande suit un changement durable, et c'est voulu")
  void thebandFollowsAlastingChangeAndThatIsIntended() {
    var state = feed(null, 300, 100, 5, 23L);
    assertThat(state.outside(400, WIDTH)).isTrue();

    // Deux cents mesures au nouveau niveau : il devient la normale. Repérer qu'un niveau a changé
    // durablement est le travail du CUSUM (M5, ADR 0038), pas celui d'une bande qui s'adapte.
    state = feed(state, 300, 400, 5, 29L);

    assertThat(state.outside(400, WIDTH)).isFalse();
    assertThat(state.level()).isCloseTo(400, org.assertj.core.data.Offset.offset(20.0));
  }

  @Test
  @DisplayName("Une série parfaitement plate ne crie pas sur un cheveu")
  void aperfectlyFlatSeriesDoesNotShoutOverAhair() {
    var state = ExponentialBand.start(100);
    for (int i = 0; i < 100; i++) {
      state = ExponentialBand.update(state, 100, ALPHA, WIDTH);
    }

    // L'écart lissé est nul : sans plancher, 100,1 sortirait de la bande.
    assertThat(state.deviation()).isLessThan(0.01);
    assertThat(state.outside(100.1, WIDTH)).isFalse();
    assertThat(state.outside(101, WIDTH)).isFalse();
    // Mais un vrai écart reste vu.
    assertThat(state.outside(200, WIDTH)).isTrue();
  }

  @Test
  @DisplayName("Le poids et la largeur sont bornés : aucun réglage ne casse le calcul")
  void theweightAndWidthAreBoundedNosettingBreaksTheComputation() {
    var state = feed(null, 50, 100, 5, 31L);

    assertThat(ExponentialBand.update(state, 120, 10, WIDTH).level()).isFinite();
    assertThat(ExponentialBand.update(state, 120, -1, WIDTH).level()).isFinite();
    assertThat(ExponentialBand.update(null, 120, ALPHA, WIDTH).samples()).isEqualTo(1);
  }

  @Test
  @DisplayName("L'écart est nul à l'intérieur de la bande, et croît au-delà")
  void theexcessIsZeroInsideTheBandAndGrowsBeyond() {
    var state = feed(null, 300, 100, 10, 37L);

    assertThat(state.excess(state.level(), WIDTH)).isZero();
    assertThat(state.excess(state.high(WIDTH), WIDTH)).isZero();
    assertThat(state.excess(state.high(WIDTH) * 2, WIDTH)).isPositive();
    assertThat(state.low(WIDTH)).isLessThan(state.level());
  }
}

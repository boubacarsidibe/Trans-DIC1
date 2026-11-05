package sn.ept.transdic1.alerting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleUnaryOperator;
import org.junit.jupiter.api.Test;
import sn.ept.transdic1.alerting.domain.ForecastStatus;
import sn.ept.transdic1.alerting.service.CapacityForecast.Point;
import sn.ept.transdic1.alerting.service.CapacityForecast.Settings;

/**
 * Calcul pur de la prévision de saturation (ADR 0020) : moindres carrés ordinaires sur (temps,
 * valeur), jours restants avant la limite, et statuts quand la prévision n'a pas de sens.
 */
class CapacityForecastTests {
  private static final Instant NOW = Instant.parse("2026-11-05T12:00:00Z");
  private static final Settings DEFAULTS = Settings.defaults();

  /** Un point par heure sur {@code days} jours jusqu'à maintenant ; x en jours (négatif). */
  private static List<Point> hourly(int days, DoubleUnaryOperator valueAtDay) {
    var points = new ArrayList<Point>();
    for (int h = days * 24; h >= 0; h--) {
      double x = -h / 24.0;
      points.add(new Point(NOW.minus(Duration.ofHours(h)), valueAtDay.applyAsDouble(x)));
    }
    return points;
  }

  @Test
  void uneCroissanceLineaireAtteintLaLimiteAuJourAttendu() {
    // 72 % maintenant, +2 points par jour : plein dans (100 - 72) / 2 = 14 jours.
    var result = CapacityForecast.compute(hourly(14, x -> 72 + 2 * x), NOW, DEFAULTS);

    assertThat(result.status()).isEqualTo(ForecastStatus.OK);
    assertThat(result.slopePerDay()).isCloseTo(2.0, within(1e-9));
    assertThat(result.current()).isCloseTo(72.0, within(1e-9));
    assertThat(result.daysToLimit()).isCloseTo(14.0, within(1e-6));
    assertThat(result.projectedAt())
        .isCloseTo(NOW.plus(Duration.ofDays(14)), within(1, ChronoUnit.SECONDS));
    assertThat(result.points()).isEqualTo(14 * 24 + 1);
    assertThat(result.limit()).isEqualTo(100.0);
  }

  @Test
  void leCoefficientDeDeterminationVautExactementUnSurUneDroiteParfaite() {
    var result = CapacityForecast.compute(hourly(5, x -> 40 + 0.5 * x), NOW, DEFAULTS);

    assertThat(result.r2()).isCloseTo(1.0, within(1e-12));
    assertThat(result.intercept()).isCloseTo(40.0, within(1e-9));
    assertThat(CapacityForecast.confidence(result.r2())).isEqualTo("haute");
  }

  @Test
  void uneSeriePlateNaPasDeTendance() {
    var result = CapacityForecast.compute(hourly(7, x -> 55), NOW, DEFAULTS);

    assertThat(result.status()).isEqualTo(ForecastStatus.NO_GROWTH);
    assertThat(result.daysToLimit()).isNull();
    assertThat(result.projectedAt()).isNull();
    assertThat(result.current()).isEqualTo(55.0);
  }

  @Test
  void uneSerieDecroissanteNaPasDeTendanceALaHausse() {
    var result = CapacityForecast.compute(hourly(7, x -> 50 - 1.5 * x), NOW, DEFAULTS);

    assertThat(result.status()).isEqualTo(ForecastStatus.NO_GROWTH);
    assertThat(result.slopePerDay()).isCloseTo(-1.5, within(1e-9));
    assertThat(result.daysToLimit()).isNull();
  }

  @Test
  void unBruitFortDonneUnR2FaibleEtUneConfianceFaible() {
    // Tendance de +0,5 point par jour noyée dans un bruit alterné de ± 10 points.
    var points = new ArrayList<Point>();
    for (int h = 14 * 24; h >= 0; h--) {
      double x = -h / 24.0;
      double noise = h % 2 == 0 ? 10 : -10;
      points.add(new Point(NOW.minus(Duration.ofHours(h)), 60 + 0.5 * x + noise));
    }

    var result = CapacityForecast.compute(points, NOW, DEFAULTS);

    assertThat(result.status()).isEqualTo(ForecastStatus.OK);
    assertThat(result.slopePerDay()).isCloseTo(0.5, within(0.05));
    assertThat(result.r2()).isLessThan(0.5);
    assertThat(CapacityForecast.confidence(result.r2())).isEqualTo("faible");
  }

  @Test
  void tropPeuDePointsOuUnePeriodeTropCourteNeDonnentPasDePrevision() {
    var fewPoints = new ArrayList<Point>();
    for (int d = 5; d >= 0; d--) {
      fewPoints.add(new Point(NOW.minus(Duration.ofDays(d)), 50 + d));
    }
    var tooShort = hourly(2, x -> 50 + 3 * x);

    var few = CapacityForecast.compute(fewPoints, NOW, DEFAULTS);
    var shortSpan = CapacityForecast.compute(tooShort, NOW, DEFAULTS);
    var empty = CapacityForecast.compute(List.of(), NOW, DEFAULTS);

    assertThat(few.status()).isEqualTo(ForecastStatus.NOT_ENOUGH_DATA);
    assertThat(few.daysToLimit()).isNull();
    assertThat(shortSpan.status()).isEqualTo(ForecastStatus.NOT_ENOUGH_DATA);
    assertThat(shortSpan.points()).isEqualTo(49);
    assertThat(empty.status()).isEqualTo(ForecastStatus.NOT_ENOUGH_DATA);
    assertThat(empty.current()).isNull();
    assertThat(empty.slopePerDay()).isNull();
  }

  @Test
  void uneValeurDejaALaLimiteEstPleine() {
    var result = CapacityForecast.compute(hourly(7, x -> Math.min(100, 100 + x)), NOW, DEFAULTS);

    assertThat(result.status()).isEqualTo(ForecastStatus.ALREADY_FULL);
    assertThat(result.daysToLimit()).isEqualTo(0.0);
    assertThat(result.projectedAt()).isEqualTo(NOW);
  }

  @Test
  void laLimiteEtLesMinimumsSeReglent() {
    var settings = new Settings(90.0, Duration.ofDays(1), 10);

    var result = CapacityForecast.compute(hourly(2, x -> 80 + 2 * x), NOW, settings);

    assertThat(result.status()).isEqualTo(ForecastStatus.OK);
    assertThat(result.daysToLimit()).isCloseTo(5.0, within(1e-6));
  }

  @Test
  void lesLibellesDeConfianceSuiventLeR2() {
    assertThat(CapacityForecast.confidence(0.8)).isEqualTo("haute");
    assertThat(CapacityForecast.confidence(0.79)).isEqualTo("moyenne");
    assertThat(CapacityForecast.confidence(0.5)).isEqualTo("moyenne");
    assertThat(CapacityForecast.confidence(0.49)).isEqualTo("faible");
    assertThat(CapacityForecast.confidence(null)).isNull();
  }
}

package sn.ept.transdic1.alerting.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sn.ept.transdic1.alerting.domain.ForecastMethod;
import sn.ept.transdic1.alerting.domain.ForecastStatus;
import sn.ept.transdic1.alerting.service.CapacityForecast.Point;
import sn.ept.transdic1.alerting.service.CapacityForecast.Settings;

/**
 * Prévision à cycle journalier (M13) : ce que le lissage saisonnier voit là où une droite se
 * trompe, et le choix entre les deux par épreuve sur les dernières heures.
 */
class SeasonalForecastTests {
  private static final Instant NOW = Instant.parse("2026-11-12T00:00:00Z");
  private static final Settings SETTINGS = Settings.defaults();

  /**
   * Série horaire avec cycle journalier : une base qui monte lentement, plus une bosse qui culmine
   * en milieu de journée.
   *
   * @param hours nombre d'heures, la dernière se terminant à {@code NOW}
   * @param base valeur de départ de la base
   * @param risePerDay montée de la base par jour
   * @param amplitude écart entre le creux de la nuit et le sommet du jour
   */
  private static List<Point> daily(int hours, double base, double risePerDay, double amplitude) {
    var points = new ArrayList<Point>(hours);
    for (int i = 0; i < hours; i++) {
      Instant at = NOW.minus(Duration.ofHours(hours - 1L - i));
      int hourOfDay = (int) Math.floorMod(at.getEpochSecond() / 3600, 24);
      double season = amplitude / 2 * Math.sin(2 * Math.PI * (hourOfDay - 6) / 24.0);
      points.add(new Point(at, base + risePerDay * i / 24.0 + season));
    }
    return points;
  }

  @Test
  @DisplayName("Sans deux cycles complets, pas de prévision saisonnière")
  void withoutTwoFullCyclesNoSeasonalForecast() {
    assertThat(SeasonalForecast.compute(daily(47, 50, 1, 20), NOW, SETTINGS)).isNull();
    assertThat(SeasonalForecast.compute(List.of(), NOW, SETTINGS)).isNull();
  }

  @Test
  @DisplayName("Le cycle journalier est reconnu et la saturation datée au pic")
  void thedailyCycleIsRecognisedAndSaturationDatedAtThePeak() {
    // Base à 70 %, +2 points par jour, cycle de 30 points d'amplitude : le pic touche 100 avant
    // que la moyenne n'en approche.
    var result = SeasonalForecast.compute(daily(24 * 10, 70, 2, 30), NOW, SETTINGS);

    assertThat(result).isNotNull();
    assertThat(result.method()).isEqualTo(ForecastMethod.SEASONAL);
    assertThat(result.status()).isEqualTo(ForecastStatus.OK);
    assertThat(result.daysToLimit()).isNotNull().isLessThan(20.0);
    // Le cycle construit culmine a midi : l'heure annoncee doit s'en approcher. C'est la
    // conversion « index du tableau saisonnier -> heure du jour » qui se verifie ici.
    assertThat(result.peakHour()).isNotNull().isBetween(10, 14);
    // La tendance est rendue par jour, comme la pente de la droite.
    assertThat(result.slopePerDay()).isNotNull().isBetween(0.5, 4.0);
  }

  @Test
  @DisplayName("Une série qui ne monte pas ne prévoit aucune saturation")
  void aseriesThatDoesNotRiseForecastsNoSaturation() {
    var result = SeasonalForecast.compute(daily(24 * 10, 40, 0, 10), NOW, SETTINGS);

    assertThat(result).isNotNull();
    assertThat(result.status()).isEqualTo(ForecastStatus.NO_GROWTH);
    assertThat(result.daysToLimit()).isNull();
  }

  @Test
  @DisplayName("Une valeur déjà à la limite est signalée comme telle")
  void avalueAlreadyAtTheLimitIsReportedAsSuch() {
    var points = new ArrayList<>(daily(24 * 10, 70, 2, 10));
    points.set(points.size() - 1, new Point(NOW, 100.0));

    var result = SeasonalForecast.compute(points, NOW, SETTINGS);

    assertThat(result).isNotNull();
    assertThat(result.status()).isEqualTo(ForecastStatus.ALREADY_FULL);
    assertThat(result.daysToLimit()).isZero();
  }

  // ------------------------------------------------------------------- choix entre les deux

  @Test
  @DisplayName("Sur une série cyclique, l'épreuve retient le modèle saisonnier")
  void onacyclicSeriesTheTrialKeepsTheSeasonalModel() {
    var points = daily(24 * 10, 50, 1, 40);

    var comparison = ForecastModels.compare(points, NOW, SETTINGS);
    var chosen = ForecastModels.choose(points, NOW, SETTINGS);

    assertThat(comparison.seasonalError()).isNotNull().isLessThan(comparison.linearError());
    assertThat(chosen.method()).isEqualTo(ForecastMethod.SEASONAL);
  }

  @Test
  @DisplayName("Sur une montée régulière sans cycle, la droite l'emporte")
  void onasteadyRiseWithoutACycleTheStraightLineWins() {
    // Aucun cycle : la droite explique tout, le modèle saisonnier n'apporte rien.
    var points = daily(24 * 10, 40, 3, 0);

    var chosen = ForecastModels.choose(points, NOW, SETTINGS);

    assertThat(chosen.method()).isEqualTo(ForecastMethod.LINEAR);
  }

  @Test
  @DisplayName("À égalité, la droite reste préférée : elle a moins de paramètres")
  void whenTiedTheStraightLineRemainsPreferred() {
    // Cycle minuscule devant la montée : le gain du modèle saisonnier ne justifie pas sa
    // complexité, et la marge l'écarte.
    var points = daily(24 * 10, 40, 5, 1);

    assertThat(ForecastModels.choose(points, NOW, SETTINGS).method())
        .isEqualTo(ForecastMethod.LINEAR);
  }

  @Test
  @DisplayName("Sans assez d'historique pour l'épreuve, la droite est conservée")
  void withoutEnoughHistoryForTheTrialTheStraightLineIsKept() {
    var points = daily(50, 50, 2, 30);

    var comparison = ForecastModels.compare(points, NOW, SETTINGS);

    assertThat(comparison.seasonalError()).isNull();
    assertThat(ForecastModels.choose(points, NOW, SETTINGS).method())
        .isEqualTo(ForecastMethod.LINEAR);
  }

  @Test
  @DisplayName("L'épreuve se fait sur des heures jamais vues par le modèle")
  void thetrialIsRunOnHoursTheModelNeverSaw() {
    var points = daily(24 * 10, 50, 1, 40);

    Double seasonal = SeasonalForecast.holdOutError(points, ForecastModels.HOLD_OUT_HOURS);

    // Un modèle ajusté sur les neuf premiers jours prévoit le dixième à quelques points près.
    assertThat(seasonal).isNotNull().isLessThan(10.0);
  }
}

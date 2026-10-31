package sn.ept.transdic1.alerting.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Mesure du détecteur sur un jeu de données synthétique reproductible (graine fixe) : 40 séries
 * horaires (charge processeur) avec cycle quotidien, creux de week-end et bruit gaussien. On
 * apprend sur 28 jours puis on juge une semaine entière : taux de fausses alertes sur les heures
 * normales, et taux de détection d'anomalies injectées (± 25 points, soit environ 8 écarts-types).
 */
class AnomalyDetectorEvaluationTests {
  private static final int SERIES = 40;
  private static final int HISTORY_DAYS = 28;
  private static final double NOISE = 3.0;
  private static final double SENSITIVITY = 3.5;

  /** Valeur normale d'une série à une heure donnée (jour 0 = lundi). */
  private static double normal(int series, int day, int hour, Random random) {
    double base = 25 + series % 7 * 3;
    double daily = 20 * Math.max(0, Math.sin((hour - 7) / 12.0 * Math.PI));
    double weekend = day % 7 >= 5 ? -12 : 0;
    return Math.max(0, base + daily + weekend + random.nextGaussian() * NOISE);
  }

  /** Normale d'un créneau comme le service : même heure, même type de jour, sur 28 jours. */
  private static List<Double> history(double[][] values, int day, int hour) {
    boolean weekend = day % 7 >= 5;
    var result = new ArrayList<Double>();
    for (int d = day - HISTORY_DAYS; d < day; d++) {
      if ((d % 7 >= 5) == weekend) {
        result.add(values[d][hour]);
      }
    }
    return result;
  }

  @Test
  void peuDeFaussesAlertesEtLesVraiesAnomaliesSontDetectees() {
    var random = new Random(42);
    int normalHours = 0;
    int falseAlarms = 0;
    int injected = 0;
    int detected = 0;
    for (int s = 0; s < SERIES; s++) {
      int days = HISTORY_DAYS + 7;
      double[][] values = new double[days][24];
      for (int d = 0; d < days; d++) {
        for (int h = 0; h < 24; h++) {
          values[d][h] = normal(s, d, h, random);
        }
      }
      for (int d = HISTORY_DAYS; d < days; d++) {
        for (int h = 0; h < 24; h++) {
          var baseline = AnomalyDetector.baseline(history(values, d, h)).orElseThrow();
          // Heure normale : une alerte serait une fausse alerte.
          normalHours++;
          if (AnomalyDetector.isAnomalous(baseline, values[d][h], SENSITIVITY)) {
            falseAlarms++;
          }
          // Même créneau avec une anomalie injectée, une heure sur six.
          if ((d * 24 + h + s) % 6 == 0) {
            injected++;
            double spike = values[d][h] + ((d + h) % 2 == 0 ? 25 : -25);
            if (AnomalyDetector.isAnomalous(baseline, spike, SENSITIVITY)) {
              detected++;
            }
          }
        }
      }
    }
    double falseRate = (double) falseAlarms / normalHours;
    double recall = (double) detected / injected;
    System.out.printf(
        "Évaluation anomalies : %d heures normales, %d fausses alertes (%.2f %%), %d anomalies,"
            + " %d détectées (%.1f %%)%n",
        normalHours, falseAlarms, falseRate * 100, injected, detected, recall * 100);
    assertThat(falseRate).isLessThan(0.01);
    assertThat(recall).isGreaterThan(0.95);
  }

  @Test
  void uneSerieTropCourteNeDonnePasDeBaseEtUneSeriePlateNEstPasHypersensible() {
    assertThat(AnomalyDetector.baseline(List.of(10.0, 11.0))).isEmpty();
    var flat = AnomalyDetector.baseline(List.of(50.0, 50.0, 50.0, 50.0)).orElseThrow();
    // Écart-type nul : le plancher (1 % de la moyenne, au moins 0,5) évite de crier au loup.
    assertThat(AnomalyDetector.isAnomalous(flat, 51.0, 3.5)).isFalse();
    assertThat(AnomalyDetector.isAnomalous(flat, 60.0, 3.5)).isTrue();
  }

  @Test
  void leRetourALaNormaleAUneMarge() {
    var b = AnomalyDetector.baseline(List.of(40.0, 42.0, 38.0, 41.0, 39.0, 40.0)).orElseThrow();
    double atThreshold = b.mean() + 3.5 * b.deviation();
    assertThat(AnomalyDetector.isBackToNormal(b, atThreshold - 0.1, 3.5)).isFalse();
    assertThat(AnomalyDetector.isBackToNormal(b, b.mean() + 2 * b.deviation(), 3.5)).isTrue();
  }
}

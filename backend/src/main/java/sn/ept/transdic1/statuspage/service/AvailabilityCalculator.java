package sn.ept.transdic1.statuspage.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import sn.ept.transdic1.statuspage.service.ComponentStateEvaluator.Window;

/**
 * Disponibilité journalière d'un composant à partir des mesures {@code probe.up} (0 ou 100 %) :
 * moyenne des mesures du jour (UTC), heures touchées par une maintenance exclues, jour sans mesure
 * = inconnu (jamais 100 %). Les mesures brutes priment ; un agrégat horaire ne sert que pour une
 * heure sans mesure brute (mesures brutes purgées après 30 jours). Arrondi par défaut à deux
 * décimales : 99,999 % s'affiche 99,99 %, jamais 100 %.
 */
public final class AvailabilityCalculator {
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  private AvailabilityCalculator() {}

  /** Mesures d'une heure : nombre et somme des valeurs (en %). */
  public record HourBucket(Instant hourStart, long samples, double sumPercent) {}

  /** Disponibilité de chaque jour (null = inconnu), du plus ancien au plus récent, et globale. */
  public record Daily(List<BigDecimal> days, BigDecimal overall) {}

  public static Daily daily(
      List<HourBucket> raw,
      List<HourBucket> hourly,
      List<Window> maintenances,
      LocalDate firstDay,
      int dayCount) {
    Map<Instant, HourBucket> merged = new HashMap<>();
    for (HourBucket bucket : hourly) {
      merged.put(bucket.hourStart(), bucket);
    }
    for (HourBucket bucket : raw) {
      merged.put(bucket.hourStart(), bucket);
    }
    long[] samples = new long[dayCount];
    double[] sums = new double[dayCount];
    for (HourBucket bucket : merged.values()) {
      if (bucket.samples() <= 0 || inMaintenance(bucket.hourStart(), maintenances)) {
        continue;
      }
      LocalDate day = bucket.hourStart().atOffset(ZoneOffset.UTC).toLocalDate();
      int index = (int) (day.toEpochDay() - firstDay.toEpochDay());
      if (index < 0 || index >= dayCount) {
        continue;
      }
      samples[index] += bucket.samples();
      sums[index] += bucket.sumPercent();
    }
    var days = new ArrayList<BigDecimal>(dayCount);
    long totalSamples = 0;
    double totalSum = 0;
    for (int i = 0; i < dayCount; i++) {
      days.add(percent(sums[i], samples[i]));
      totalSamples += samples[i];
      totalSum += sums[i];
    }
    return new Daily(Collections.unmodifiableList(days), percent(totalSum, totalSamples));
  }

  /** Vrai si l'heure [hourStart, hourStart + 1 h[ chevauche une maintenance. */
  private static boolean inMaintenance(Instant hourStart, List<Window> maintenances) {
    Instant hourEnd = hourStart.plus(Duration.ofHours(1));
    return maintenances.stream()
        .anyMatch(w -> w.start().isBefore(hourEnd) && w.end().isAfter(hourStart));
  }

  private static BigDecimal percent(double sum, long samples) {
    if (samples <= 0) {
      return null;
    }
    BigDecimal value =
        BigDecimal.valueOf(sum).divide(BigDecimal.valueOf(samples), 6, RoundingMode.HALF_EVEN);
    if (value.compareTo(HUNDRED) > 0) {
      value = HUNDRED;
    }
    if (value.compareTo(BigDecimal.ZERO) < 0) {
      value = BigDecimal.ZERO;
    }
    return value.setScale(2, RoundingMode.DOWN);
  }
}

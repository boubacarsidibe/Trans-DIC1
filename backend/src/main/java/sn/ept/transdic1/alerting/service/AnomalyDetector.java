package sn.ept.transdic1.alerting.service;

import java.util.List;
import java.util.Optional;

/**
 * Calcul pur, sans base de données, de la détection d'anomalies : une base de comportement (moyenne
 * et écart-type des moyennes horaires passées au même créneau) et un score en nombre
 * d'écarts-types. Séparé du service pour être testé et mesuré sur des jeux de données.
 */
public final class AnomalyDetector {
  /** Nombre minimal de valeurs passées pour juger : en dessous, pas de base fiable. */
  public static final int MIN_SAMPLES = 3;

  private AnomalyDetector() {}

  /**
   * Au-delà de ce nombre d'écarts médians autour de la médiane, une valeur passée est tenue pour un
   * ancien incident et écartée de la normale. Mesuré sur le jeu d'évaluation : 5 garde les fausses
   * alertes sous 1 % sur un historique propre et détecte encore plus de 95 % des anomalies quand 8
   * % de l'historique est pollué (moyenne et écart-type bruts : environ un tiers).
   */
  static final double OUTLIER_CUT = 5.0;

  /** Rend l'écart absolu médian comparable à un écart-type pour une loi normale. */
  private static final double MAD_TO_SIGMA = 1.4826;

  /**
   * Base de comportement d'un créneau.
   *
   * @param floor écart-type minimal retenu, pour ne pas crier à l'anomalie sur une série
   *     parfaitement plate (1 % de l'amplitude habituelle, ou 0,5 unité)
   * @param excluded valeurs passées écartées parce qu'elles étaient elles-mêmes inhabituelles
   */
  public record Baseline(double mean, double deviation, double floor, int samples, int excluded) {
    /** Écart en nombre d'écarts-types (signé : positif au-dessus de la normale). */
    public double score(double value) {
      return (value - mean) / Math.max(deviation, floor);
    }
  }

  /**
   * Normale robuste : les valeurs passées trop éloignées de la médiane (anciens incidents) sont
   * écartées, puis la moyenne et l'écart-type sont calculés sur le reste. Sans cela, un incident
   * passé gonfle l'écart-type du créneau et masque les suivants.
   */
  public static Optional<Baseline> baseline(List<Double> history) {
    if (history.size() < MIN_SAMPLES) {
      return Optional.empty();
    }
    double median = median(history);
    double spread = MAD_TO_SIGMA * median(history.stream().map(v -> Math.abs(v - median)).toList());
    double limit = OUTLIER_CUT * Math.max(spread, floor(median));
    List<Double> kept = history.stream().filter(v -> Math.abs(v - median) <= limit).toList();
    if (kept.size() < MIN_SAMPLES) {
      kept = history;
    }
    List<Double> used = kept;
    double mean = used.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    double variance =
        used.stream().mapToDouble(v -> (v - mean) * (v - mean)).sum() / (used.size() - 1);
    return Optional.of(
        new Baseline(
            mean, Math.sqrt(variance), floor(mean), used.size(), history.size() - used.size()));
  }

  private static double floor(double center) {
    return Math.max(0.5, Math.abs(center) * 0.01);
  }

  private static double median(List<Double> values) {
    double[] sorted = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
    int middle = sorted.length / 2;
    return sorted.length % 2 == 1 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2;
  }

  /** Vrai si la valeur est anormale pour la sensibilité donnée (en écarts-types). */
  public static boolean isAnomalous(Baseline baseline, double value, double sensitivity) {
    return Math.abs(baseline.score(value)) >= sensitivity;
  }

  /** Retour à la normale : sous 70 % du seuil, pour éviter les alertes qui clignotent. */
  public static boolean isBackToNormal(Baseline baseline, double value, double sensitivity) {
    return Math.abs(baseline.score(value)) < sensitivity * 0.7;
  }
}

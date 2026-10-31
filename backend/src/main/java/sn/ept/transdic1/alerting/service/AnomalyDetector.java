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
   * Base de comportement d'un créneau.
   *
   * @param floor écart-type minimal retenu, pour ne pas crier à l'anomalie sur une série
   *     parfaitement plate (1 % de l'amplitude habituelle, ou 0,5 unité)
   */
  public record Baseline(double mean, double deviation, double floor, int samples) {
    /** Écart en nombre d'écarts-types (signé : positif au-dessus de la normale). */
    public double score(double value) {
      return (value - mean) / Math.max(deviation, floor);
    }
  }

  public static Optional<Baseline> baseline(List<Double> history) {
    if (history.size() < MIN_SAMPLES) {
      return Optional.empty();
    }
    double mean = history.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    double variance =
        history.stream().mapToDouble(v -> (v - mean) * (v - mean)).sum() / (history.size() - 1);
    double floor = Math.max(0.5, Math.abs(mean) * 0.01);
    return Optional.of(new Baseline(mean, Math.sqrt(variance), floor, history.size()));
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

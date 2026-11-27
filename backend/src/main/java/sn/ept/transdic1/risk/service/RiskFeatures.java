package sn.ept.transdic1.risk.service;

import java.util.List;
import java.util.Locale;

/**
 * Variables du modèle de risque de panne : ce que l'on sait d'un équipement sur 24 heures, mis en
 * nombres de même ordre de grandeur (entre 0 et environ 3). Calcul pur.
 *
 * <p>Les comptes passent par {@code log(1 + n)} : la dixième alerte de la journée apprend moins que
 * la première. Les pics d'occupation ne comptent qu'au-delà de 80 %, ramenés entre 0 et 1.
 */
public final class RiskFeatures {
  private RiskFeatures() {}

  /**
   * Une variable : son nom technique, son libellé, son poids initial (avant tout apprentissage).
   */
  public record Feature(String name, String label, double priorWeight) {}

  /**
   * Poids initiaux, fixés par raisonnement : ils servent tant que la plateforme n'a pas observé de
   * pannes, puis l'apprentissage les corrige. L'ordonnée initiale ({@link #PRIOR_INTERCEPT}) donne
   * environ 2 % de risque à un équipement sans aucun signe.
   */
  public static final List<Feature> FEATURES =
      List.of(
          new Feature("anomalies", "Comportements inhabituels", 0.8),
          new Feature("warnings", "Avertissements", 0.5),
          new Feature("forecast", "Prévision de saturation en cours", 1.2),
          new Feature("flapping", "Alerte instable", 0.8),
          new Feature("cpuPeak", "Pic de processeur", 1.2),
          new Feature("memoryPeak", "Pic de mémoire", 1.5),
          new Feature("diskPeak", "Pic d'occupation disque", 2.0),
          new Feature("failures7d", "Pertes de collecte sur 7 jours", 1.0),
          new Feature("noData", "Indicateurs devenus muets", 0.7));

  public static final double PRIOR_INTERCEPT = -4.0;

  /** Faits bruts d'un équipement sur 24 heures. Un pic absent (pas de mesure) vaut nul. */
  public record Facts(
      int anomalies,
      int warnings,
      int forecasts,
      boolean flapping,
      Double cpuPeak,
      Double memoryPeak,
      Double diskPeak,
      int failures7d,
      int noData) {

    public static Facts empty() {
      return new Facts(0, 0, 0, false, null, null, null, 0, 0);
    }
  }

  public static double[] priorWeights() {
    double[] w = new double[FEATURES.size() + 1];
    w[0] = PRIOR_INTERCEPT;
    for (int j = 0; j < FEATURES.size(); j++) {
      w[j + 1] = FEATURES.get(j).priorWeight();
    }
    return w;
  }

  public static double[] vector(Facts f) {
    return new double[] {
      Math.log1p(f.anomalies()),
      Math.log1p(f.warnings()),
      f.forecasts() > 0 ? 1 : 0,
      f.flapping() ? 1 : 0,
      percent(f.cpuPeak()),
      percent(f.memoryPeak()),
      percent(f.diskPeak()),
      Math.log1p(f.failures7d()),
      f.noData() > 0 ? 1 : 0
    };
  }

  /** Texte lisible du fait derrière une variable, pour expliquer un score. */
  public static String describe(String feature, Facts f) {
    return switch (feature) {
      case "anomalies" -> f.anomalies() + " alerte(s) de comportement inhabituel en 24 h";
      case "warnings" -> f.warnings() + " avertissement(s) en 24 h";
      case "forecast" -> "Prévision de saturation en cours";
      case "flapping" -> "Une alerte clignote (ouvertures répétées)";
      case "cpuPeak" -> "Processeur jusqu'à " + whole(f.cpuPeak()) + " % en 24 h";
      case "memoryPeak" -> "Mémoire jusqu'à " + whole(f.memoryPeak()) + " % en 24 h";
      case "diskPeak" -> "Disque jusqu'à " + whole(f.diskPeak()) + " % en 24 h";
      case "failures7d" -> f.failures7d() + " perte(s) de collecte sur 7 jours";
      case "noData" -> f.noData() + " indicateur(s) devenu(s) muet(s)";
      default -> feature;
    };
  }

  /** Seuil sous lequel une occupation ne dit rien du risque de panne. */
  static final double SATURATION_FROM = 80;

  /**
   * Part de la zone de saturation atteinte : 0 jusqu'à 80 %, 1 à 100 %. L'occupation brute comptait
   * comme variable, et c'était une erreur visible : un poste au repos (disque à 86 %, mémoire à 77
   * %, processeur à 65 %) recevait 41 % de risque à 24 h avec les seuls poids initiaux. Un disque à
   * moitié plein n'est pas un signe de panne ; un disque qui approche de 100 % en est un.
   */
  private static double percent(Double value) {
    if (value == null || value.isNaN()) {
      return 0;
    }
    return Math.max(0, Math.min(1, (value - SATURATION_FROM) / (100 - SATURATION_FROM)));
  }

  private static String whole(Double value) {
    return value == null ? "?" : String.format(Locale.FRANCE, "%.0f", value);
  }
}

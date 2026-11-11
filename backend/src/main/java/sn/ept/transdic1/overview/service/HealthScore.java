package sn.ept.transdic1.overview.service;

import java.util.ArrayList;
import java.util.List;

/**
 * Score de santé d'un équipement (M3) : un seul chiffre de 0 à 100 qui résume son statut, ses
 * alertes actives, son silence éventuel et la part de ses indicateurs hors de leur normale.
 *
 * <p>Calcul déterministe, sans apprentissage : on part de 100 et on retranche des pénalités
 * plafonnées, pour qu'un équipement couvert d'alertes d'information ne tombe pas au même niveau
 * qu'un équipement en panne. Chaque pénalité appliquée est rendue en clair, afin que l'écran
 * explique le chiffre.
 */
public final class HealthScore {
  /** Pénalité par alerte active, et plafond cumulé par sévérité. */
  static final int CRITICAL_EACH = 25;

  static final int CRITICAL_CAP = 60;
  static final int WARNING_EACH = 8;
  static final int WARNING_CAP = 30;
  static final int INFO_EACH = 2;
  static final int INFO_CAP = 10;

  /** Statut dégradé annoncé par la collecte. */
  static final int DEGRADED = 15;

  /** Équipement muet : il a déjà parlé, mais plus rien depuis dix minutes. */
  static final int SILENT = 20;

  /** Poids du taux d'anomalie : tous les indicateurs hors normale coûtent 40 points. */
  static final int ANOMALY_WEIGHT = 40;

  private HealthScore() {}

  /**
   * Santé d'un équipement.
   *
   * @param score 0 à 100, ou null quand l'équipement est en maintenance (on ne juge pas un
   *     équipement qu'on est en train de manipuler)
   * @param level {@code bon}, {@code surveiller}, {@code degrade} ou {@code critique}
   * @param reasons ce qui a retiré des points, du plus coûteux au moins coûteux
   */
  public record Health(Integer score, String level, List<String> reasons) {}

  /**
   * @param status statut opérationnel de l'équipement
   * @param critical alertes critiques actives
   * @param warning alertes d'avertissement actives
   * @param info alertes d'information actives
   * @param silent plus aucune mesure depuis dix minutes alors que l'équipement a déjà parlé
   * @param anomalyRate part des indicateurs hors de leur normale (0 à 1), ou null si non calculée
   */
  public static Health of(
      String status, int critical, int warning, int info, boolean silent, Double anomalyRate) {
    if ("MAINTENANCE".equals(status)) {
      return new Health(null, "maintenance", List.of("En maintenance : la santé n'est pas jugée"));
    }
    var reasons = new ArrayList<String>();
    if ("DOWN".equals(status)) {
      reasons.add("Hors service");
      return new Health(0, "critique", List.copyOf(reasons));
    }
    int penalty = 0;
    if (critical > 0) {
      int cost = Math.min(critical * CRITICAL_EACH, CRITICAL_CAP);
      penalty += cost;
      reasons.add(plural(critical, "alerte critique", "alertes critiques") + " (−" + cost + ")");
    }
    if ("DEGRADED".equals(status)) {
      penalty += DEGRADED;
      reasons.add("Fonctionnement dégradé (−" + DEGRADED + ")");
    }
    if (silent) {
      penalty += SILENT;
      reasons.add("Aucune mesure depuis dix minutes (−" + SILENT + ")");
    }
    if (warning > 0) {
      int cost = Math.min(warning * WARNING_EACH, WARNING_CAP);
      penalty += cost;
      reasons.add(plural(warning, "avertissement", "avertissements") + " (−" + cost + ")");
    }
    if (anomalyRate != null && anomalyRate > 0) {
      int cost = (int) Math.round(Math.min(1, anomalyRate) * ANOMALY_WEIGHT);
      if (cost > 0) {
        penalty += cost;
        reasons.add(
            "Indicateurs hors de leur normale : "
                + Math.round(anomalyRate * 100)
                + " % (−"
                + cost
                + ")");
      }
    }
    if (info > 0) {
      int cost = Math.min(info * INFO_EACH, INFO_CAP);
      penalty += cost;
      reasons.add(plural(info, "information", "informations") + " (−" + cost + ")");
    }
    int score = Math.max(0, 100 - penalty);
    if (reasons.isEmpty()) {
      reasons.add("Rien à signaler");
    }
    return new Health(score, level(score), List.copyOf(reasons));
  }

  /** Santé d'un site : moyenne des équipements jugés ; aucun équipement jugé donne null. */
  public static Health ofSite(List<Integer> scores) {
    List<Integer> judged = scores.stream().filter(java.util.Objects::nonNull).toList();
    if (judged.isEmpty()) {
      return new Health(null, "maintenance", List.of("Aucun équipement à juger"));
    }
    int average =
        (int) Math.round(judged.stream().mapToInt(Integer::intValue).average().orElse(100));
    long weak = judged.stream().filter(value -> value < 60).count();
    List<String> reasons =
        weak == 0
            ? List.of("Tous les équipements sont au vert")
            : List.of(plural((int) weak, "équipement en souffrance", "équipements en souffrance"));
    return new Health(average, level(average), reasons);
  }

  static String level(int score) {
    if (score >= 85) {
      return "bon";
    }
    if (score >= 60) {
      return "surveiller";
    }
    return score >= 30 ? "degrade" : "critique";
  }

  private static String plural(int count, String one, String many) {
    return count + " " + (count > 1 ? many : one);
  }
}

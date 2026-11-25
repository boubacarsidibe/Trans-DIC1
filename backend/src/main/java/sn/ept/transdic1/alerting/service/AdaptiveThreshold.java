package sn.ept.transdic1.alerting.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import sn.ept.transdic1.alerting.domain.AlertRule;
import sn.ept.transdic1.alerting.domain.ComparisonOperator;

/**
 * Calcul pur du seuil adaptatif (ADR 0049), sans base de données.
 *
 * <p>Pour une règle « supérieur à », l'historique est la suite des <b>pointes horaires</b> (maximum
 * de chaque heure) du même créneau ; pour « inférieur à », celle des creux (minimum de chaque
 * heure). Le seuil est la normale robuste de ces pointes plus {@code sensibilité} écarts-types
 * (moins pour « inférieur à ») ; le retour à la normale est à 70 % de cette marge, comme pour les
 * anomalies, pour que l'alerte ne clignote pas.
 *
 * <p>Les pointes et non les moyennes : la règle compare chaque mesure brute, et une mesure brute
 * dépasse la moyenne de son heure à peu près une fois sur deux. Comparée aux pointes habituelles,
 * elle ne les dépasse que lorsqu'il se passe quelque chose.
 */
public final class AdaptiveThreshold {
  /** Heures du même créneau nécessaires pour apprendre : en dessous, le seuil de départ sert. */
  public static final int MIN_SAMPLES = 6;

  /** Part de la marge à laquelle l'alerte se résout. */
  static final double RECOVERY_RATIO = 0.7;

  private AdaptiveThreshold() {}

  /**
   * Seuils en vigueur pour une mesure.
   *
   * @param learned faux pendant l'apprentissage : ce sont alors les seuils écrits dans la règle
   * @param normal normale robuste des pointes (ou des creux) du créneau, nulle sans apprentissage
   * @param deviation écart retenu (plancher compris), nul sans apprentissage
   * @param capped vrai quand la limite absolue a remplacé le seuil appris
   * @param slot créneau lu, en clair (« en semaine vers 14 h »)
   */
  public record Limits(
      BigDecimal threshold,
      BigDecimal recovery,
      boolean learned,
      BigDecimal normal,
      BigDecimal deviation,
      int samples,
      boolean capped,
      String slot) {

    public boolean breachedBy(ComparisonOperator operator, BigDecimal value) {
      return operator.test(value, threshold);
    }

    /** Même règle que {@link AlertRule#isRecoveredBy}, avec les seuils en vigueur. */
    public boolean recoveredBy(ComparisonOperator operator, BigDecimal value) {
      if (recovery == null) {
        return !breachedBy(operator, value);
      }
      return switch (operator) {
        case GREATER_THAN, GREATER_OR_EQUAL -> value.compareTo(recovery) <= 0;
        case LESS_THAN, LESS_OR_EQUAL -> value.compareTo(recovery) >= 0;
        case EQUAL -> value.compareTo(threshold) != 0;
      };
    }
  }

  /** Les seuils écrits dans la règle : règle fixe, ou apprentissage pas encore possible. */
  public static Limits fixed(AlertRule rule, int samples, String slot) {
    return new Limits(
        rule.getThreshold(), rule.getRecoveryThreshold(), false, null, null, samples, false, slot);
  }

  /** Vrai si la règle compare « au-dessus de » : l'historique utile est alors celui des pointes. */
  public static boolean watchesPeaks(ComparisonOperator operator) {
    return operator == ComparisonOperator.GREATER_THAN
        || operator == ComparisonOperator.GREATER_OR_EQUAL;
  }

  /**
   * Seuils appris sur l'historique du créneau, ou ceux de la règle si l'historique est trop court.
   *
   * @param history pointes horaires (règle « supérieur à ») ou creux horaires (« inférieur à »)
   */
  public static Limits learn(AlertRule rule, List<Double> history, String slot) {
    if (history.size() < MIN_SAMPLES || rule.getOperator() == ComparisonOperator.EQUAL) {
      return fixed(rule, history.size(), slot);
    }
    var baseline = AnomalyDetector.baseline(history).orElse(null);
    if (baseline == null) {
      return fixed(rule, history.size(), slot);
    }
    double sensitivity = rule.getAdaptiveSensitivity().doubleValue();
    double spread = Math.max(baseline.deviation(), baseline.floor());
    boolean up = watchesPeaks(rule.getOperator());
    double sign = up ? 1 : -1;
    double threshold = baseline.mean() + sign * sensitivity * spread;
    double recovery = baseline.mean() + sign * sensitivity * spread * RECOVERY_RATIO;
    boolean capped = false;
    BigDecimal limit = rule.getAdaptiveLimit();
    if (limit != null) {
      double bound = limit.doubleValue();
      // La limite absolue n'est jamais plus permissive que ce qu'elle promet.
      if (up ? threshold > bound : threshold < bound) {
        threshold = bound;
        recovery = up ? Math.min(recovery, bound) : Math.max(recovery, bound);
        capped = true;
      }
    }
    return new Limits(
        round(threshold),
        round(recovery),
        true,
        round(baseline.mean()),
        round(spread),
        baseline.samples(),
        capped,
        slot);
  }

  private static BigDecimal round(double value) {
    return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP);
  }
}

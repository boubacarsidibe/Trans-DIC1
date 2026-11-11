package sn.ept.transdic1.diagnostic.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Ce qui a bougé en même temps que l'alerte : on compare, indicateur par indicateur, la moyenne
 * juste avant l'ouverture et la moyenne autour de l'ouverture. Un classement par ampleur du
 * changement donne à l'opérateur les pistes que l'alerte seule ne montre pas (la mémoire qui monte
 * en même temps que le processeur, le trafic qui s'effondre quand le lien se dégrade).
 *
 * <p>Ce n'est pas une preuve de cause : deux choses qui bougent ensemble peuvent n'avoir aucun
 * lien. L'écran le dit, et le diagnostic ne s'en sert pas pour classer ses causes.
 */
public final class MetricMovement {
  /**
   * Variation minimale, en pourcentage, pour qu'un indicateur vaille la peine d'être montré : en
   * dessous, c'est du bruit de mesure.
   */
  public static final double MIN_CHANGE_PERCENT = 20.0;

  /** Points de mesure minimaux de chaque côté : deux valeurs ne font pas une moyenne. */
  public static final int MIN_POINTS = 3;

  private MetricMovement() {}

  /**
   * Variation en pourcentage entre avant et pendant, ou null si elle n'a pas de sens.
   *
   * <p>Le dénominateur est protégé par un plancher : passer de 0,01 à 0,05 n'est pas une hausse de
   * 400 % digne d'intérêt. Le plancher vaut 1 % de la plus grande des deux valeurs, au minimum 0,5
   * dans l'unité de l'indicateur.
   */
  public static Double changePercent(Double before, Double during) {
    if (before == null || during == null) {
      return null;
    }
    double floor = Math.max(0.5, Math.max(Math.abs(before), Math.abs(during)) * 0.01);
    double base = Math.max(Math.abs(before), floor);
    double change = (during - before) / base * 100;
    return round(change);
  }

  /** Vrai si le changement mérite d'être montré. */
  public static boolean isNotable(Double changePercent, int pointsBefore, int pointsDuring) {
    return changePercent != null
        && pointsBefore >= MIN_POINTS
        && pointsDuring >= MIN_POINTS
        && Math.abs(changePercent) >= MIN_CHANGE_PERCENT;
  }

  static double round(double value) {
    return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).doubleValue();
  }
}

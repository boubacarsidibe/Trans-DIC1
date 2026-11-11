package sn.ept.transdic1.alerting.service;

import java.util.List;
import java.util.Optional;

/**
 * Équipement qui se comporte autrement que ses semblables : sur un même indicateur, un commutateur
 * à 80 % de processeur quand les neuf autres du même site sont à 10 % mérite un regard, même si
 * aucun seuil n'est franchi et même si c'est son habitude (une anomalie ne le verrait pas).
 *
 * <p>La comparaison se fait à la médiane du groupe, en écarts absolus médians : une poignée de
 * machines très chargées ne déplace pas la référence, contrairement à une moyenne.
 */
public final class PeerOutlier {
  /** En dessous, un groupe est trop petit pour que « se démarquer » veuille dire quelque chose. */
  public static final int MIN_PEERS = 4;

  /** Rend l'écart absolu médian comparable à un écart-type pour une loi normale. */
  private static final double MAD_TO_SIGMA = 1.4826;

  private PeerOutlier() {}

  /**
   * Référence d'un groupe : sa valeur habituelle et sa dispersion.
   *
   * @param floor dispersion minimale retenue, pour qu'un groupe parfaitement homogène ne fasse pas
   *     d'un écart d'un point une affaire d'État
   */
  public record Group(double median, double spread, double floor, int size) {
    /** Écart d'une valeur à la médiane du groupe, en nombre d'écarts. */
    public double score(double value) {
      return (value - median) / Math.max(spread, floor);
    }
  }

  /**
   * Référence d'un groupe de valeurs, ou vide si le groupe est trop petit.
   *
   * @param floorRatio part de la médiane retenue comme dispersion plancher
   * @param floorMinimum dispersion plancher absolue, dans l'unité de l'indicateur
   */
  public static Optional<Group> group(List<Double> values, double floorRatio, double floorMinimum) {
    if (values.size() < MIN_PEERS) {
      return Optional.empty();
    }
    double median = median(values);
    double spread = MAD_TO_SIGMA * median(values.stream().map(v -> Math.abs(v - median)).toList());
    double floor = Math.max(floorMinimum, Math.abs(median) * floorRatio);
    return Optional.of(new Group(median, spread, floor, values.size()));
  }

  /**
   * Vrai si la valeur se démarque du groupe. Un seul sens compte : seul ce qui est plus haut que
   * les semblables intéresse (un disque moins rempli ou un processeur moins chargé n'est pas un
   * problème).
   */
  public static boolean standsOut(Group group, double value, double sensitivity) {
    return group.score(value) >= sensitivity;
  }

  /** Retour dans le rang : sous 70 % du seuil, pour que l'alerte n'alterne pas. */
  public static boolean isBackInLine(Group group, double value, double sensitivity) {
    return group.score(value) < sensitivity * 0.7;
  }

  private static double median(List<Double> values) {
    double[] sorted = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
    int middle = sorted.length / 2;
    return sorted.length % 2 == 1 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2;
  }
}

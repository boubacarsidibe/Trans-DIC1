package sn.ept.transdic1.flow.service;

import java.util.Collection;

/**
 * Entropie de Shannon d'une répartition, pour distinguer un trafic concentré d'un trafic éparpillé
 * (M7 du plan, ADR 0033).
 *
 * <p>C'est la mesure qui sépare un balayage d'un usage ordinaire. Un serveur de sauvegarde parle à
 * cinquante machines, mais très inégalement : presque tout le volume va vers deux ou trois d'entre
 * elles, et l'entropie reste basse. Un scanner touche ses cibles de la même façon, une fois chacune
 * : la répartition est parfaitement plate et l'entropie atteint son maximum.
 *
 * <p>L'entropie brute dépend du nombre de catégories : comparer 10 cibles et 1 000 cibles n'aurait
 * pas de sens. On rend donc la valeur <b>normalisée</b>, divisée par l'entropie maximale possible
 * ({@code log2(n)}) : 0 pour « tout va au même endroit », 1 pour « également réparti ».
 */
public final class FlowEntropy {
  private static final double LOG2 = Math.log(2);

  private FlowEntropy() {}

  /**
   * Entropie normalisée, entre 0 et 1.
   *
   * <p>Une seule catégorie rend 0 : il n'y a rien d'éparpillé dans un trafic qui n'a qu'une seule
   * destination. Une répartition vide rend 0 également.
   */
  public static double normalised(Collection<Long> counts) {
    if (counts == null || counts.size() < 2) {
      return 0;
    }
    double total = 0;
    for (long count : counts) {
      if (count > 0) {
        total += count;
      }
    }
    if (total <= 0) {
      return 0;
    }
    double entropy = 0;
    for (long count : counts) {
      if (count <= 0) {
        continue;
      }
      double share = count / total;
      entropy -= share * (Math.log(share) / LOG2);
    }
    double maximum = Math.log(counts.size()) / LOG2;
    return maximum <= 0 ? 0 : Math.min(1, entropy / maximum);
  }
}

package sn.ept.transdic1.alerting.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Règles d'association sur les alertes (M9, ADR 0042) : quels équipements tombent <b>ensemble</b>,
 * assez souvent pour que ce ne soit pas un hasard ?
 *
 * <p>C'est la méthode des paniers de supermarché, appliquée au temps. Un « panier » est une fenêtre
 * de quelques minutes ; les « articles » sont les équipements qui y ont eu une alerte. Deux
 * équipements qui se retrouvent souvent dans le même panier sont probablement liés par quelque
 * chose — un serveur et le stockage qu'il monte, une baie et son onduleur, deux machines derrière
 * le même cordon mal déclaré — que la topologie saisie à la main ne connaît pas.
 *
 * <p><b>Support</b> : part des fenêtres où les deux apparaissent ensemble. Il est petit par nature
 * et sert surtout à écarter les coïncidences uniques. <b>Confiance</b> : part des fenêtres de l'un
 * où l'autre apparaît aussi — et l'on retient la plus forte des deux directions, parce qu'une panne
 * d'onduleur entraîne toujours la baie, tandis que la baie tombe parfois seule.
 *
 * <p><b>Pourquoi découper le temps en fenêtres fixes.</b> « Dans les dix minutes » ne définit pas
 * des paniers : si A arrive à 10 h 00, B à 10 h 08 et C à 10 h 15, A et B sont ensemble, B et C
 * aussi, mais A et C non — et aucun découpage ne rend cela cohérent. Des fenêtres fixes donnent des
 * paniers nets, au prix connu qu'une paire séparée par une frontière de fenêtre est manquée. C'est
 * un compromis assumé : la méthode cherche ce qui se répète, et une paire vraiment liée ne tombera
 * pas toujours du mauvais côté de la frontière.
 *
 * <p>Calcul pur, sans Spring ni base.
 */
public final class AssociationRules {

  private AssociationRules() {}

  /**
   * Une alerte réduite à ce qui compte ici : quand, et sur quel équipement.
   *
   * @param window numéro de la fenêtre de temps, déjà calculé
   */
  public record Observation(long window, UUID equipmentId) {}

  /**
   * Une paire d'équipements et ce qu'on a compté sur elle.
   *
   * @param first identifiant le plus petit des deux, pour que la paire n'existe qu'une fois
   * @param together fenêtres où les deux ont eu une alerte
   * @param firstWindows fenêtres où le premier en a eu une
   * @param secondWindows fenêtres où le second en a eu une
   * @param totalWindows fenêtres où il y a eu au moins une alerte, tous équipements confondus
   */
  public record Pair(
      UUID first,
      UUID second,
      int together,
      int firstWindows,
      int secondWindows,
      int totalWindows) {

    /** Part des fenêtres où les deux apparaissent ensemble. */
    public double support() {
      return totalWindows == 0 ? 0 : (double) together / totalWindows;
    }

    /**
     * Part des fenêtres de l'un où l'autre apparaît aussi, dans la direction la plus forte. On
     * garde la plus forte parce qu'un lien est rarement symétrique : l'onduleur entraîne la baie,
     * la baie tombe parfois seule.
     */
    public double confidence() {
      double toSecond = firstWindows == 0 ? 0 : (double) together / firstWindows;
      double toFirst = secondWindows == 0 ? 0 : (double) together / secondWindows;
      return Math.max(toSecond, toFirst);
    }
  }

  /**
   * Compte les paires d'équipements qui apparaissent ensemble.
   *
   * @param minTogether nombre minimal de fenêtres communes : en dessous, c'est une coïncidence
   * @param minConfidence confiance minimale dans la direction la plus forte
   * @param excluded paires à ne pas proposer, chacune dans l'ordre canonique ; typiquement celles
   *     que la topologie connaît déjà, puisque c'est justement ce qu'on cherche à compléter
   * @return paires retenues, de la plus confiante à la moins confiante
   */
  public static List<Pair> mine(
      List<Observation> observations,
      int minTogether,
      double minConfidence,
      Set<List<UUID>> excluded) {
    if (observations == null || observations.isEmpty()) {
      return List.of();
    }
    // Un équipement peut avoir plusieurs alertes dans la même fenêtre : il n'y compte qu'une fois,
    // sinon une machine bavarde ferait grimper tous les comptes.
    var byWindow = new HashMap<Long, Set<UUID>>();
    for (Observation observation : observations) {
      byWindow
          .computeIfAbsent(observation.window(), window -> new HashSet<>())
          .add(observation.equipmentId());
    }
    int totalWindows = byWindow.size();
    var windowsOf = new HashMap<UUID, Integer>();
    var togetherOf = new HashMap<List<UUID>, Integer>();
    for (Set<UUID> present : byWindow.values()) {
      List<UUID> sorted = present.stream().sorted(order()).toList();
      for (UUID equipment : sorted) {
        windowsOf.merge(equipment, 1, Integer::sum);
      }
      for (int i = 0; i < sorted.size(); i++) {
        for (int j = i + 1; j < sorted.size(); j++) {
          togetherOf.merge(List.of(sorted.get(i), sorted.get(j)), 1, Integer::sum);
        }
      }
    }
    var pairs = new ArrayList<Pair>();
    for (var entry : togetherOf.entrySet()) {
      if (entry.getValue() < minTogether) {
        continue;
      }
      if (excluded != null && excluded.contains(entry.getKey())) {
        continue;
      }
      UUID first = entry.getKey().get(0);
      UUID second = entry.getKey().get(1);
      var pair =
          new Pair(
              first,
              second,
              entry.getValue(),
              windowsOf.getOrDefault(first, 0),
              windowsOf.getOrDefault(second, 0),
              totalWindows);
      if (pair.confidence() >= minConfidence) {
        pairs.add(pair);
      }
    }
    pairs.sort(
        Comparator.comparingDouble(Pair::confidence)
            .reversed()
            .thenComparing(Comparator.comparingInt(Pair::together).reversed())
            .thenComparing(Pair::first, order())
            .thenComparing(Pair::second, order()));
    return pairs;
  }

  /** Numéro de la fenêtre d'un instant, pour des fenêtres de {@code lengthMillis}. */
  public static long window(java.time.Instant at, long lengthMillis) {
    if (lengthMillis <= 0) {
      throw new IllegalArgumentException("Fenêtre : une longueur positive est attendue");
    }
    return Math.floorDiv(at.toEpochMilli(), lengthMillis);
  }

  /**
   * Paire dans l'ordre canonique, pour comparer sans se soucier du sens.
   *
   * <p>L'ordre est celui de <b>PostgreSQL</b>, c'est-à-dire octet par octet, et non celui de {@link
   * UUID#compareTo}, qui compare deux {@code long} <i>signés</i> : un identifiant commençant par
   * {@code ff} passe avant un {@code 00} en Java et après en base. La table porte une contrainte
   * sur cet ordre, et c'est elle qui a révélé la différence ; la comparaison des représentations
   * textuelles, elle, suit exactement l'ordre des octets.
   */
  public static List<UUID> canonical(UUID one, UUID other) {
    return one.toString().compareTo(other.toString()) <= 0
        ? List.of(one, other)
        : List.of(other, one);
  }

  /** Comparateur dans l'ordre de PostgreSQL, pour trier les équipements d'une fenêtre. */
  public static Comparator<UUID> order() {
    return Comparator.comparing(UUID::toString);
  }

  /** Les deux équipements d'une paire, dans l'ordre canonique, à partir d'une table de liens. */
  public static Set<List<UUID>> canonical(Map<UUID, Set<UUID>> links) {
    var pairs = new HashSet<List<UUID>>();
    links.forEach((from, targets) -> targets.forEach(to -> pairs.add(canonical(from, to))));
    return pairs;
  }
}

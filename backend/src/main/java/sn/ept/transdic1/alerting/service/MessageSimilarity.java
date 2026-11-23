package sn.ept.transdic1.alerting.service;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Ressemblance entre deux messages d'alerte (M10, ADR 0039) : un nombre entre 0 et 1, utilisé comme
 * <b>troisième</b> indice du regroupement en incidents, après l'équipement et la topologie.
 *
 * <p>La mesure est un indice de Jaccard sur les mots : la part de vocabulaire commun dans le
 * vocabulaire total. Avant de compter, les messages sont ramenés à leur squelette — accents et
 * ponctuation retirés, <b>chiffres supprimés</b> (ce sont eux qui diffèrent d'une alerte à
 * l'autre), mots courts écartés, et les noms propres passés en paramètre retirés eux aussi. Sans
 * cela, « Processeur à 92 % sur Routeur A » et « Processeur à 87 % sur Commutateur B » ne se
 * ressembleraient que de loin, alors que c'est le même fait.
 *
 * <p><b>Ce que cette mesure vaut, et ne vaut pas.</b> Nos messages viennent de gabarits : deux
 * alertes du même détecteur sur le même indicateur se ressemblent donc presque parfaitement, et la
 * ressemblance ne fait alors que confirmer ce que le type et l'indicateur disaient déjà. Elle
 * apporte quelque chose dans un seul cas, mais réel : deux détecteurs différents qui décrivent le
 * même fait avec les mêmes mots — un seuil « Débit entrant au-dessus de 90 % » et une anomalie «
 * Débit entrant inhabituel » sur deux machines voisines. En revanche elle ne rapproche pas deux
 * formulations du même fait qui ne partagent pas de vocabulaire (« Interface coupée » et « Plus de
 * données reçues ») : pour cela il n'y a que la topologie. C'est mesuré dans l'ADR, pas supposé.
 *
 * <p>Calcul pur, sans Spring ni base.
 */
public final class MessageSimilarity {

  /** En dessous de quatre lettres, un mot est un article ou une unité, pas un sujet. */
  private static final int MIN_WORD = 4;

  /**
   * Mots trop répandus dans nos gabarits pour distinguer quoi que ce soit : ils apparaissent dans
   * presque toutes les alertes et gonfleraient toutes les ressemblances.
   */
  private static final Set<String> IGNORED =
      Set.of(
          "alerte",
          "alertes",
          "pour",
          "dans",
          "depuis",
          "avec",
          "sans",
          "plus",
          "moins",
          "habituellement",
          "habitude",
          "cette",
          "celui",
          "celle",
          "etait",
          "aucune",
          "aucun",
          "equipement",
          "machine",
          "valeur",
          "mesure",
          "mesures");

  private MessageSimilarity() {}

  /**
   * Part de vocabulaire commun entre deux messages, entre 0 (rien de commun) et 1 (même squelette).
   *
   * @param names noms propres à retirer avant comparaison (noms d'équipements, de sites) : ils
   *     diffèrent toujours et feraient baisser la ressemblance de deux alertes identiques
   */
  public static double between(String first, String second, String... names) {
    Set<String> left = words(first, names);
    Set<String> right = words(second, names);
    if (left.isEmpty() || right.isEmpty()) {
      // Un message vide, ou réduit à des chiffres : on ne conclut rien plutôt que de conclure 1.
      return 0;
    }
    var common = new HashSet<>(left);
    common.retainAll(right);
    var union = new HashSet<>(left);
    union.addAll(right);
    return (double) common.size() / union.size();
  }

  /** Squelette d'un message : les mots qui portent le sens, sans accents, chiffres ni noms. */
  static Set<String> words(String message, String... names) {
    if (message == null || message.isBlank()) {
      return Set.of();
    }
    var excluded = new HashSet<String>(IGNORED);
    for (String name : names) {
      excluded.addAll(plain(name));
    }
    var words = new LinkedHashSet<String>();
    for (String word : plain(message)) {
      if (word.length() >= MIN_WORD && !excluded.contains(word)) {
        words.add(word);
      }
    }
    return words;
  }

  /** Mots d'un texte, en minuscules, sans accents, sans chiffres et sans ponctuation. */
  private static java.util.List<String> plain(String text) {
    if (text == null) {
      return java.util.List.of();
    }
    String flattened =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "")
            .toLowerCase(java.util.Locale.ROOT)
            // Les chiffres sont retirés et non gardés comme mots : ce sont eux qui changent d'une
            // alerte à l'autre, alors que le fait décrit est le même.
            .replaceAll("[^a-z]+", " ");
    return Arrays.stream(flattened.split(" ")).filter(word -> !word.isBlank()).toList();
  }
}

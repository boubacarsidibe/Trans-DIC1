package sn.ept.transdic1.diagnostic.service;

import java.util.Arrays;
import java.util.Comparator;
import java.util.stream.IntStream;

/**
 * Corrélation de rang de Spearman (M11, ADR 0040) : deux indicateurs <b>varient-ils ensemble</b> au
 * moment d'une alerte ?
 *
 * <p>C'est ce que la comparaison avant/après de {@link MetricMovement} ne peut pas voir. Celle-ci
 * compare deux moyennes : elle repère une marche d'escalier, et rien d'autre. Un indicateur qui
 * monte et descend <i>en même temps</i> que la mesure en alerte, sans changer de niveau moyen, lui
 * est invisible — et c'est souvent le signal le plus parlant : la latence qui suit la charge, le
 * trafic qui s'effondre à chaque pic d'erreurs.
 *
 * <p><b>Pourquoi les rangs et non les valeurs.</b> La corrélation de Pearson suppose une relation
 * droite et se laisse emporter par une seule valeur extrême — or une alerte, c'est justement un
 * extrême. Spearman ne regarde que l'<i>ordre</i> des valeurs : elle voit une relation montante
 * même courbe (une latence qui s'envole quand la charge approche de la saturation) et une pointe
 * isolée ne lui coûte qu'un rang.
 *
 * <p><b>Les égalités comptent.</b> Nos séries en sont pleines : un compteur qui ne bouge pas, un
 * pourcentage arrondi. La formule abrégée {@code 1 - 6Σd²/(n³-n)}, celle de tous les manuels, est
 * fausse dès qu'il y a des égalités ; on calcule donc un Pearson sur des rangs moyennés, ce qui est
 * la définition et reste juste dans tous les cas.
 *
 * <p>Calcul pur, sans Spring ni base.
 */
public final class RankCorrelation {

  /** Constantes de l'approximation de la loi normale (Zelen et Severo, 26.2.17). */
  private static final double[] COEFFICIENTS = {
    0.319381530, -0.356563782, 1.781477937, -1.821255978, 1.330274429
  };

  private RankCorrelation() {}

  /**
   * Ce que la corrélation dit d'un couple de séries.
   *
   * @param rho corrélation de rang, entre -1 et 1
   * @param points nombre d'instants où les deux indicateurs ont une valeur
   * @param pValue probabilité d'obtenir un {@code rho} au moins aussi fort par pur hasard, si les
   *     deux séries n'avaient aucun lien
   */
  public record Result(double rho, int points, double pValue) {

    /** Vrai si les deux indicateurs vont dans le même sens. */
    public boolean rising() {
      return rho >= 0;
    }
  }

  /**
   * Corrélation de rang entre deux séries de même longueur, alignées instant par instant.
   *
   * @return null si le calcul n'a pas de sens : moins de {@code minPoints} instants, ou une série
   *     constante — une droite plate n'a pas d'ordre, et la corréler à quoi que ce soit n'a aucun
   *     sens, pas même « zéro »
   */
  public static Result between(double[] first, double[] second, int minPoints) {
    if (first == null || second == null || first.length != second.length) {
      throw new IllegalArgumentException("Corrélation de rang : deux séries alignées attendues");
    }
    int n = first.length;
    if (n < Math.max(4, minPoints)) {
      return null;
    }
    double[] left = ranks(first);
    double[] right = ranks(second);
    if (left == null || right == null) {
      return null;
    }
    double rho = pearson(left, right);
    if (Double.isNaN(rho)) {
      return null;
    }
    return new Result(clamp(rho), n, pValue(rho, n));
  }

  /**
   * Rangs d'une série, les égalités recevant le rang moyen de leur groupe ; null si la série est
   * constante.
   */
  static double[] ranks(double[] values) {
    int n = values.length;
    Integer[] order = IntStream.range(0, n).boxed().toArray(Integer[]::new);
    Arrays.sort(order, Comparator.comparingDouble(index -> values[index]));
    double[] ranks = new double[n];
    int start = 0;
    while (start < n) {
      int end = start;
      while (end + 1 < n && values[order[end + 1]] == values[order[start]]) {
        end++;
      }
      // Rang moyen du groupe d'égalités : (start + end) / 2, en rangs commençant à 1.
      double average = (start + end) / 2.0 + 1;
      for (int i = start; i <= end; i++) {
        ranks[order[i]] = average;
      }
      start = end + 1;
    }
    // Tout le monde au même rang : la série est constante, il n'y a rien à corréler.
    return ranks[order[0]] == ranks[order[n - 1]] ? null : ranks;
  }

  /** Corrélation de Pearson, appliquée ici à des rangs. NaN si une série est sans dispersion. */
  static double pearson(double[] first, double[] second) {
    int n = first.length;
    double meanFirst = 0;
    double meanSecond = 0;
    for (int i = 0; i < n; i++) {
      meanFirst += first[i];
      meanSecond += second[i];
    }
    meanFirst /= n;
    meanSecond /= n;
    double covariance = 0;
    double varianceFirst = 0;
    double varianceSecond = 0;
    for (int i = 0; i < n; i++) {
      double a = first[i] - meanFirst;
      double b = second[i] - meanSecond;
      covariance += a * b;
      varianceFirst += a * a;
      varianceSecond += b * b;
    }
    if (varianceFirst <= 0 || varianceSecond <= 0) {
      return Double.NaN;
    }
    return covariance / Math.sqrt(varianceFirst * varianceSecond);
  }

  /**
   * Probabilité d'obtenir une corrélation au moins aussi forte par pur hasard, par la
   * transformation de Fisher : {@code z = atanh(rho) * sqrt(n - 3)} suit à peu près une loi normale
   * centrée réduite quand les deux séries n'ont aucun lien.
   *
   * <p>Ce chiffre répond à « est-ce du hasard ? », pas à « est-ce intéressant ? ». Sur deux cents
   * points, une corrélation de 0,2 n'est pas du hasard et n'apprend rien à personne : c'est
   * pourquoi le service exige <b>aussi</b> une corrélation forte en valeur absolue.
   */
  static double pValue(double rho, int n) {
    if (n <= 3) {
      return 1;
    }
    double bounded = clamp(rho);
    if (Math.abs(bounded) >= 1) {
      return 0;
    }
    double z = Math.abs(0.5 * Math.log((1 + bounded) / (1 - bounded))) * Math.sqrt(n - 3.0);
    return Math.min(1, 2 * normalTail(z));
  }

  /** Aire de la queue droite de la loi normale centrée réduite au-delà de {@code z}. */
  static double normalTail(double z) {
    if (z < 0) {
      return 1 - normalTail(-z);
    }
    double t = 1 / (1 + 0.2316419 * z);
    double polynomial = 0;
    double power = t;
    for (double coefficient : COEFFICIENTS) {
      polynomial += coefficient * power;
      power *= t;
    }
    return polynomial * Math.exp(-z * z / 2) / Math.sqrt(2 * Math.PI);
  }

  private static double clamp(double value) {
    return Math.max(-1, Math.min(1, value));
  }
}

package sn.ept.transdic1.risk.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Régression logistique écrite à la main, sans bibliothèque : probabilité = sigmoïde(b + Σ wᵢ·xᵢ).
 * Calcul pur et déterministe (mêmes données, mêmes poids, au bit près), vérifiable sur des jeux
 * écrits à la main.
 *
 * <p><b>Apprentissage.</b> Descente de gradient sur la vraisemblance, avec une pénalité L2 qui
 * empêche un poids de partir à l'infini quand une variable sépare parfaitement quelques cas — ce
 * qui arrive vite sur un petit parc. Pas de rééquilibrage des classes : on veut une probabilité
 * <i>calibrée</i> (« 12 % de risque »), pas seulement un classement.
 *
 * <p><b>Évaluation.</b> L'aire sous la courbe ROC (AUC : probabilité qu'une journée suivie d'une
 * panne soit notée plus haut qu'une journée sans panne ; 0,5 = hasard, 1 = parfait) et le score de
 * Brier (erreur quadratique moyenne des probabilités ; 0 = parfait).
 */
public final class LogisticModel {
  private LogisticModel() {}

  /** Poids appris : {@code weights[0]} est l'ordonnée, puis un poids par variable. */
  public static double[] train(
      List<double[]> features, int[] labels, double l2, double learningRate, int iterations) {
    int n = features.size();
    int p = n == 0 ? 0 : features.get(0).length;
    double[] w = new double[p + 1];
    if (n == 0) {
      return w;
    }
    double positives = Arrays.stream(labels).sum();
    // Départ à l'ordonnée du taux de base : la descente converge plus vite et reste stable.
    double base = Math.min(Math.max(positives / n, 1e-4), 1 - 1e-4);
    w[0] = Math.log(base / (1 - base));
    double[] gradient = new double[p + 1];
    for (int it = 0; it < iterations; it++) {
      Arrays.fill(gradient, 0);
      for (int i = 0; i < n; i++) {
        double[] x = features.get(i);
        double error = sigmoid(score(w, x)) - labels[i];
        gradient[0] += error;
        for (int j = 0; j < p; j++) {
          gradient[j + 1] += error * x[j];
        }
      }
      w[0] -= learningRate * gradient[0] / n;
      for (int j = 1; j <= p; j++) {
        w[j] -= learningRate * (gradient[j] / n + l2 * w[j]);
      }
    }
    return w;
  }

  public static double probability(double[] weights, double[] x) {
    return sigmoid(score(weights, x));
  }

  static double score(double[] weights, double[] x) {
    double s = weights[0];
    for (int j = 0; j < x.length; j++) {
      s += weights[j + 1] * x[j];
    }
    return s;
  }

  static double sigmoid(double z) {
    if (z >= 0) {
      return 1 / (1 + Math.exp(-z));
    }
    double e = Math.exp(z);
    return e / (1 + e);
  }

  /** AUC par la statistique de Mann-Whitney ; nulle s'il manque une des deux classes. */
  public static Double auc(double[] scores, int[] labels) {
    int n = scores.length;
    List<Integer> order = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      order.add(i);
    }
    order.sort(Comparator.comparingDouble(i -> scores[i]));
    double[] ranks = new double[n];
    int i = 0;
    while (i < n) {
      int j = i;
      while (j + 1 < n && scores[order.get(j + 1)] == scores[order.get(i)]) {
        j++;
      }
      double rank = (i + j) / 2.0 + 1; // rang moyen des ex aequo
      for (int k = i; k <= j; k++) {
        ranks[order.get(k)] = rank;
      }
      i = j + 1;
    }
    long pos = Arrays.stream(labels).filter(l -> l == 1).count();
    long neg = n - pos;
    if (pos == 0 || neg == 0) {
      return null;
    }
    double sumPos = 0;
    for (int k = 0; k < n; k++) {
      if (labels[k] == 1) {
        sumPos += ranks[k];
      }
    }
    return (sumPos - pos * (pos + 1) / 2.0) / (pos * (double) neg);
  }

  public static Double brier(double[] probabilities, int[] labels) {
    if (probabilities.length == 0) {
      return null;
    }
    double sum = 0;
    for (int i = 0; i < probabilities.length; i++) {
      double d = probabilities[i] - labels[i];
      sum += d * d;
    }
    return sum / probabilities.length;
  }

  /**
   * Poids mélangés : les poids appris sont tirés vers les poids initiaux d'autant plus fort que les
   * pannes observées sont rares — {@code (pannes × appris + k × initial) / (pannes + k)}. Sans
   * panne, ce sont exactement les poids initiaux ; avec beaucoup de pannes, l'expérience l'emporte.
   * C'est la même règle que les poids du diagnostic (ADR 0045) : pas de bascule brutale.
   */
  public static double[] blend(double[] learned, double[] prior, int positives, double k) {
    double[] w = new double[prior.length];
    for (int j = 0; j < w.length; j++) {
      w[j] = (positives * learned[j] + k * prior[j]) / (positives + k);
    }
    return w;
  }
}

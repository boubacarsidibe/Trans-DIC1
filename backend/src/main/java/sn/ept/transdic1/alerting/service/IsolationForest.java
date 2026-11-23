package sn.ept.transdic1.alerting.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Forêt d'isolement (M2 du plan, ADR 0037) : repère un état inhabituel en regardant toutes les
 * mesures d'un équipement <b>ensemble</b>, là où chacune prise à part reste dans sa normale.
 *
 * <p>Le principe tient en une phrase : un point rare est facile à isoler. On découpe l'espace au
 * hasard, encore et encore ; un point ordinaire, noyé parmi ses semblables, demande beaucoup de
 * découpes avant de se retrouver seul, tandis qu'un point à l'écart s'isole en quelques-unes. La
 * profondeur moyenne à laquelle un point finit seul, sur une centaine d'arbres, devient son score.
 *
 * <p>Calcul pur, sans Spring ni base, et sans bibliothèque ajoutée : l'algorithme tient en deux
 * cents lignes et se vérifie sur des cas écrits à la main. Une dépendance de plus se justifierait
 * mal pour cela.
 *
 * <p><b>Découpes obliques</b> (forêt d'isolement étendue). La version d'origine découpe toujours
 * perpendiculairement à un axe : « processeur sous 42 % », « latence au-dessus de 18 ms ». Elle a
 * un angle mort exactement là où se trouve notre intérêt : une machine peu chargée qui répond
 * lentement reste dans l'étendue de chaque colonne prise à part, et aucune découpe droite ne
 * l'isole. Mesuré sur un jeu d'épreuve, un tel point obtenait un score inférieur au 95ᵉ centile des
 * heures ordinaires — donc invisible. Les découpes se font donc selon une direction tirée au
 * hasard, ce qui sépare « charge faible mais latence haute » de « charge forte et latence haute ».
 *
 * <p>Les mesures sont ramenées à une échelle commune avant le tirage (centrées, divisées par
 * l'écart-type de leur colonne) : une direction oblique mélange les colonnes, et sans cela un
 * nombre d'octets écraserait un pourcentage. Les découpes droites n'en avaient pas besoin ;
 * celles-ci, si.
 */
public final class IsolationForest {
  /** Arbres de la forêt : au-delà, le score ne bouge plus guère. */
  public static final int DEFAULT_TREES = 100;

  /** Points tirés par arbre, comme dans l'article d'origine. */
  public static final int DEFAULT_SAMPLE = 256;

  /** Constante d'Euler-Mascheroni, pour la longueur moyenne d'un chemin de recherche. */
  private static final double EULER = 0.5772156649015329;

  private final List<Node> trees;
  private final int sampleSize;
  private final int features;
  private final double[] centre;
  private final double[] scale;

  private IsolationForest(
      List<Node> trees, int sampleSize, int features, double[] centre, double[] scale) {
    this.trees = trees;
    this.sampleSize = sampleSize;
    this.features = features;
    this.centre = centre;
    this.scale = scale;
  }

  /**
   * Nœud d'un arbre : soit une découpe oblique (produit scalaire avec {@code normal} sous {@code
   * split}), soit une feuille qui compte ce qu'elle contient.
   */
  private record Node(double[] normal, double split, Node left, Node right, int size) {
    boolean isLeaf() {
      return left == null;
    }
  }

  /**
   * Construit la forêt.
   *
   * @param data une ligne par instant observé, une colonne par indicateur
   * @param seed graine fixe : deux calculs sur les mêmes données donnent le même score, sans quoi
   *     une alerte apparaîtrait et disparaîtrait d'un passage à l'autre
   */
  public static IsolationForest fit(double[][] data, int trees, int sampleSize, long seed) {
    if (data == null || data.length == 0 || data[0].length == 0) {
      throw new IllegalArgumentException("Forêt d'isolement : aucune donnée");
    }
    int features = data[0].length;
    double[] centre = new double[features];
    double[] scale = new double[features];
    for (int f = 0; f < features; f++) {
      double mean = 0;
      for (double[] row : data) {
        mean += row[f];
      }
      mean /= data.length;
      double variance = 0;
      for (double[] row : data) {
        variance += (row[f] - mean) * (row[f] - mean);
      }
      centre[f] = mean;
      // Colonne constante : l'échelle vaut presque zéro, on la plancher pour ne pas diviser par 0 ;
      // la colonne ne pèsera simplement rien dans les directions tirées.
      scale[f] = Math.max(1e-9, Math.sqrt(variance / Math.max(1, data.length - 1)));
    }
    double[][] scaled = new double[data.length][features];
    for (int i = 0; i < data.length; i++) {
      for (int f = 0; f < features; f++) {
        scaled[i][f] = (data[i][f] - centre[f]) / scale[f];
      }
    }
    int sample = Math.max(8, Math.min(sampleSize, scaled.length));
    int height = (int) Math.ceil(Math.log(sample) / Math.log(2));
    var random = new Random(seed);
    var built = new ArrayList<Node>(trees);
    for (int t = 0; t < Math.max(1, trees); t++) {
      built.add(build(subsample(scaled, sample, random), 0, height, random));
    }
    return new IsolationForest(List.copyOf(built), sample, features, centre, scale);
  }

  /**
   * Score d'anomalie entre 0 et 1 : au-dessus de 0,5 le point s'isole plus vite que la moyenne,
   * proche de 1 il est franchement à l'écart. Les valeurs usuelles d'un seuil vont de 0,6 à 0,7.
   */
  public double score(double[] point) {
    if (point == null || point.length != features) {
      throw new IllegalArgumentException(
          "Forêt d'isolement : " + features + " indicateurs attendus");
    }
    double[] scaled = new double[features];
    for (int f = 0; f < features; f++) {
      scaled[f] = (point[f] - centre[f]) / scale[f];
    }
    double total = 0;
    for (Node tree : trees) {
      total += pathLength(tree, scaled, 0);
    }
    double average = total / trees.size();
    return Math.pow(2, -average / averagePath(sampleSize));
  }

  /** Nombre d'indicateurs attendus par point. */
  public int features() {
    return features;
  }

  private static double[][] subsample(double[][] data, int size, Random random) {
    if (size >= data.length) {
      return data;
    }
    // Tirage sans remise : un même instant deux fois dans un arbre fausserait la profondeur.
    int[] indexes = new int[data.length];
    for (int i = 0; i < indexes.length; i++) {
      indexes[i] = i;
    }
    for (int i = 0; i < size; i++) {
      int j = i + random.nextInt(indexes.length - i);
      int swap = indexes[i];
      indexes[i] = indexes[j];
      indexes[j] = swap;
    }
    var sample = new double[size][];
    for (int i = 0; i < size; i++) {
      sample[i] = data[indexes[i]];
    }
    return sample;
  }

  private static Node build(double[][] data, int depth, int height, Random random) {
    if (depth >= height || data.length <= 1) {
      return new Node(null, 0, null, null, data.length);
    }
    // Une direction au hasard, et une découpe au hasard dans l'étendue que les points couvrent
    // selon cette direction.
    int features = data[0].length;
    double[] normal = new double[features];
    for (int f = 0; f < features; f++) {
      normal[f] = random.nextGaussian();
    }
    double min = Double.MAX_VALUE;
    double max = -Double.MAX_VALUE;
    double[] projections = new double[data.length];
    for (int i = 0; i < data.length; i++) {
      projections[i] = dot(data[i], normal);
      min = Math.min(min, projections[i]);
      max = Math.max(max, projections[i]);
    }
    if (min == max) {
      // Tous les points confondus selon cette direction : rien à découper de ce côté.
      return new Node(null, 0, null, null, data.length);
    }
    double split = min + random.nextDouble() * (max - min);
    var left = new ArrayList<double[]>();
    var right = new ArrayList<double[]>();
    for (int i = 0; i < data.length; i++) {
      if (projections[i] < split) {
        left.add(data[i]);
      } else {
        right.add(data[i]);
      }
    }
    if (left.isEmpty() || right.isEmpty()) {
      return new Node(null, 0, null, null, data.length);
    }
    return new Node(
        normal,
        split,
        build(left.toArray(double[][]::new), depth + 1, height, random),
        build(right.toArray(double[][]::new), depth + 1, height, random),
        data.length);
  }

  private static double dot(double[] point, double[] normal) {
    double total = 0;
    for (int i = 0; i < normal.length; i++) {
      total += point[i] * normal[i];
    }
    return total;
  }

  private static double pathLength(Node node, double[] point, int depth) {
    if (node.isLeaf()) {
      // La feuille contient peut-être encore plusieurs points : on ajoute la profondeur qu'il
      // aurait fallu en moyenne pour les séparer, plutôt que d'arrêter net.
      return depth + averagePath(node.size());
    }
    return dot(point, node.normal()) < node.split()
        ? pathLength(node.left(), point, depth + 1)
        : pathLength(node.right(), point, depth + 1);
  }

  /** Profondeur moyenne d'une recherche infructueuse dans un arbre binaire de {@code n} points. */
  static double averagePath(int n) {
    if (n <= 1) {
      return 0;
    }
    if (n == 2) {
      return 1;
    }
    double harmonic = Math.log(n - 1.0) + EULER;
    return 2 * harmonic - 2.0 * (n - 1) / n;
  }
}

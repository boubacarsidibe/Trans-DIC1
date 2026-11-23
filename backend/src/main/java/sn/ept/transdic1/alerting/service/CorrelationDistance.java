package sn.ept.transdic1.alerting.service;

/**
 * Distance de Mahalanobis : à quel point un état s'écarte des <b>liens habituels</b> entre les
 * mesures d'un équipement (M2, ADR 0037).
 *
 * <p>Elle complète la forêt d'isolement, et les deux ne voient pas la même chose. La forêt repère
 * ce qui est globalement rare — un point loin de tout. Elle est en revanche faible quand l'état
 * suspect tombe à l'intérieur de l'étendue de chaque mesure : une machine à 35 % de processeur qui
 * répond en 23 ms reste dans les plages observées des deux colonnes, et la forêt ne la classe qu'au
 * 97ᵉ centile. La distance, elle, sait que la latence suit d'ordinaire la charge, et voit aussitôt
 * que ce couple-là n'a jamais existé.
 *
 * <p>Calcul pur. La matrice de covariance est inversée par élimination de Gauss-Jordan ; avec huit
 * indicateurs au plus, c'est immédiat. Une petite valeur est ajoutée sur la diagonale avant
 * inversion : sans elle, deux indicateurs parfaitement liés (ou une colonne constante) rendraient
 * la matrice non inversible.
 */
public final class CorrelationDistance {
  /** Ajout sur la diagonale, proportionnel à la variance moyenne, pour pouvoir inverser. */
  private static final double RIDGE = 1e-6;

  private final double[] mean;
  private final double[][] inverse;

  private CorrelationDistance(double[] mean, double[][] inverse) {
    this.mean = mean;
    this.inverse = inverse;
  }

  /**
   * Apprend la forme habituelle du nuage.
   *
   * @param data une ligne par instant observé, une colonne par indicateur
   * @return null si les données ne permettent pas d'estimer la covariance (trop peu de lignes)
   */
  public static CorrelationDistance fit(double[][] data) {
    if (data == null || data.length < 3 || data[0].length == 0) {
      return null;
    }
    int features = data[0].length;
    if (data.length <= features) {
      // Moins de lignes que de colonnes : la covariance serait dégénérée.
      return null;
    }
    double[] mean = new double[features];
    for (double[] row : data) {
      for (int f = 0; f < features; f++) {
        mean[f] += row[f];
      }
    }
    for (int f = 0; f < features; f++) {
      mean[f] /= data.length;
    }
    double[][] covariance = new double[features][features];
    for (double[] row : data) {
      for (int i = 0; i < features; i++) {
        for (int j = 0; j < features; j++) {
          covariance[i][j] += (row[i] - mean[i]) * (row[j] - mean[j]);
        }
      }
    }
    double trace = 0;
    for (int i = 0; i < features; i++) {
      for (int j = 0; j < features; j++) {
        covariance[i][j] /= data.length - 1.0;
      }
      trace += covariance[i][i];
    }
    double ridge = Math.max(1e-12, RIDGE * trace / features);
    for (int i = 0; i < features; i++) {
      covariance[i][i] += ridge;
    }
    double[][] inverse = invert(covariance);
    return inverse == null ? null : new CorrelationDistance(mean, inverse);
  }

  /**
   * Distance de l'état au nuage habituel, en « écarts » : 0 au centre, et d'autant plus grande que
   * l'état rompt avec les liens observés. Pour des mesures à peu près normales, une valeur au-delà
   * de 4 ou 5 est déjà remarquable.
   */
  public double distance(double[] point) {
    if (point == null || point.length != mean.length) {
      throw new IllegalArgumentException("Distance : " + mean.length + " indicateurs attendus");
    }
    double[] centred = new double[mean.length];
    for (int i = 0; i < mean.length; i++) {
      centred[i] = point[i] - mean[i];
    }
    double total = 0;
    for (int i = 0; i < mean.length; i++) {
      double row = 0;
      for (int j = 0; j < mean.length; j++) {
        row += inverse[i][j] * centred[j];
      }
      total += centred[i] * row;
    }
    return total <= 0 ? 0 : Math.sqrt(total);
  }

  /** Inverse par élimination de Gauss-Jordan avec pivot partiel ; null si singulière. */
  static double[][] invert(double[][] matrix) {
    int n = matrix.length;
    double[][] work = new double[n][2 * n];
    for (int i = 0; i < n; i++) {
      System.arraycopy(matrix[i], 0, work[i], 0, n);
      work[i][n + i] = 1;
    }
    for (int column = 0; column < n; column++) {
      int pivot = column;
      for (int row = column + 1; row < n; row++) {
        if (Math.abs(work[row][column]) > Math.abs(work[pivot][column])) {
          pivot = row;
        }
      }
      if (Math.abs(work[pivot][column]) < 1e-12) {
        return null;
      }
      double[] swap = work[column];
      work[column] = work[pivot];
      work[pivot] = swap;
      double divisor = work[column][column];
      for (int j = 0; j < 2 * n; j++) {
        work[column][j] /= divisor;
      }
      for (int row = 0; row < n; row++) {
        if (row == column) {
          continue;
        }
        double factor = work[row][column];
        if (factor == 0) {
          continue;
        }
        for (int j = 0; j < 2 * n; j++) {
          work[row][j] -= factor * work[column][j];
        }
      }
    }
    double[][] inverse = new double[n][n];
    for (int i = 0; i < n; i++) {
      System.arraycopy(work[i], n, inverse[i], 0, n);
    }
    return inverse;
  }
}

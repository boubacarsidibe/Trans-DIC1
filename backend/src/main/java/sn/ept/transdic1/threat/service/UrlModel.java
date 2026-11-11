package sn.ept.transdic1.threat.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * Modèle appris qui juge la forme d'une requête web (S4 du plan).
 *
 * <p>C'est une régression logistique sur les suites de trois caractères de la requête ({@link
 * UrlFeatures}) : chaque suite apporte un poids, positif si elle est plus fréquente dans les
 * attaques, négatif si elle l'est dans les requêtes ordinaires. La somme passe par une sigmoïde et
 * donne une probabilité entre 0 et 1.
 *
 * <p>La somme est divisée par la racine du nombre de suites distinctes. Sans cela, une URL longue
 * accumulerait des poids du seul fait de sa longueur et un catalogue bien rempli deviendrait
 * suspect.
 *
 * <p><b>Ce que ce modèle n'est pas.</b> Il ne remplace pas les signatures : il les complète pour
 * les formes qu'aucune règle ne couvre. Il ne dit pas <i>pourquoi</i> : on peut montrer les suites
 * qui ont le plus pesé, pas une famille d'attaque. Et il a été appris sur un corpus construit par
 * nous ; sur le trafic réel de l'école, son taux de fausses alertes reste à mesurer. C'est la
 * raison pour laquelle, seul, il n'ouvre jamais d'alerte critique.
 */
public final class UrlModel {
  /** Modèle livré avec l'application, appris sur le corpus du dépôt. */
  public static final String RESOURCE = "/threat/modele-url.txt";

  private final float[] weights;
  private final float bias;

  UrlModel(float[] weights, float bias) {
    if (weights.length != UrlFeatures.BUCKETS) {
      throw new IllegalArgumentException(
          "Modèle attendu sur " + UrlFeatures.BUCKETS + " cases, reçu " + weights.length);
    }
    this.weights = weights;
    this.bias = bias;
  }

  /** Modèle qui ne juge rien : tout vaut 0. Sert quand le modèle est coupé par configuration. */
  public static UrlModel disabled() {
    return new UrlModel(new float[UrlFeatures.BUCKETS], -100f);
  }

  /** Charge le modèle livré avec l'application. */
  public static UrlModel fromResource() {
    try (InputStream input = UrlModel.class.getResourceAsStream(RESOURCE)) {
      if (input == null) {
        throw new IllegalStateException("Modèle d'URL absent du classpath : " + RESOURCE);
      }
      return read(new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8)));
    } catch (IOException e) {
      throw new IllegalStateException("Modèle d'URL illisible : " + RESOURCE, e);
    }
  }

  /** Charge un modèle réappris par l'exploitation sur son propre trafic. */
  public static UrlModel fromFile(Path path) {
    try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      return read(reader);
    } catch (IOException e) {
      throw new IllegalStateException("Modèle d'URL illisible : " + path, e);
    }
  }

  /**
   * Lit le format du modèle : des lignes {@code clé valeur}, les commentaires commencent par {@code
   * #}. Seuls les poids non nuls sont écrits, sous la forme {@code poids case valeur}.
   */
  static UrlModel read(BufferedReader reader) throws IOException {
    float[] weights = new float[UrlFeatures.BUCKETS];
    Float bias = null;
    String line;
    while ((line = reader.readLine()) != null) {
      line = line.strip();
      if (line.isEmpty() || line.startsWith("#")) {
        continue;
      }
      String[] parts = line.split("\\s+");
      switch (parts[0]) {
        case "cases" ->
            require(Integer.parseInt(parts[1]) == UrlFeatures.BUCKETS, "nombre de cases inattendu");
        case "suite" ->
            require(
                Integer.parseInt(parts[1]) == UrlFeatures.NGRAM, "longueur de suite inattendue");
        case "biais" -> bias = Float.parseFloat(parts[1]);
        case "poids" -> weights[Integer.parseInt(parts[1])] = Float.parseFloat(parts[2]);
        default -> {
          // Ligne inconnue (version ultérieure du format) : ignorée, pas de quoi tout arrêter.
        }
      }
    }
    require(bias != null, "biais absent");
    return new UrlModel(weights, bias);
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException("Modèle d'URL invalide : " + message);
    }
  }

  /** Probabilité, entre 0 et 1, que la requête soit une charge utile d'attaque. */
  public double probability(String request) {
    Set<Integer> features = UrlFeatures.of(request);
    if (features.isEmpty()) {
      return 0;
    }
    double sum = 0;
    for (int bucket : features) {
      sum += weights[bucket];
    }
    return sigmoid(bias + sum / Math.sqrt(features.size()));
  }

  static double sigmoid(double value) {
    if (value >= 0) {
      return 1 / (1 + Math.exp(-value));
    }
    double exp = Math.exp(value);
    return exp / (1 + exp);
  }

  float[] weights() {
    return weights;
  }

  float bias() {
    return bias;
  }
}

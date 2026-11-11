package sn.ept.transdic1.threat.service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * Apprentissage du modèle d'URL (S4) : régression logistique sur les suites de trois caractères.
 *
 * <p>L'apprentissage est <b>reproductible</b> : même corpus, même modèle, au bit près. Un test le
 * vérifie, pour que le fichier livré reste ce que le corpus du dépôt produit, et non un vestige
 * d'une exécution oubliée.
 *
 * <p>Les deux classes sont rééquilibrées : un corpus qui contiendrait deux fois plus d'attaques que
 * de requêtes ordinaires apprendrait au modèle à voir des attaques partout.
 *
 * <p>Pour réapprendre sur le trafic d'un établissement (c'est le seul vrai remède aux fausses
 * alertes) :
 *
 * <pre>
 * java -cp trans-dic1-backend.jar sn.ept.transdic1.threat.service.UrlModelTrainer \
 *      requetes-normales.txt requetes-attaques.txt modele-url.txt
 * </pre>
 *
 * <p>puis désigner le fichier produit par la propriété {@code threat.model.file}.
 */
public final class UrlModelTrainer {
  /** Passages sur le corpus. */
  static final int EPOCHS = 40;

  /** Pas d'apprentissage initial, divisé au fil des passages. */
  static final double LEARNING_RATE = 0.5;

  /** Pénalité qui garde les poids petits : sans elle, le modèle apprend le corpus par cœur. */
  static final double L2 = 1e-5;

  /** Graine fixe : l'ordre de présentation des exemples ne doit pas dépendre du hasard du jour. */
  static final long SEED = 20261111L;

  /** Un exemple : les cases occupées par la requête, et ce qu'elle est. */
  public record Sample(Set<Integer> features, boolean attack) {}

  private UrlModelTrainer() {}

  /** Construit un exemple à partir d'une requête brute. */
  public static Sample sample(String request, boolean attack) {
    return new Sample(UrlFeatures.of(request), attack);
  }

  /** Apprend un modèle sur les exemples donnés. */
  public static UrlModel train(List<Sample> samples) {
    List<Sample> usable = samples.stream().filter(s -> !s.features().isEmpty()).toList();
    if (usable.isEmpty()) {
      throw new IllegalArgumentException("Corpus vide : rien à apprendre");
    }
    long attacks = usable.stream().filter(Sample::attack).count();
    long normals = usable.size() - attacks;
    if (attacks == 0 || normals == 0) {
      throw new IllegalArgumentException("Corpus à une seule classe : rien à distinguer");
    }
    // Poids de classe : chaque classe pèse autant, quel que soit le nombre d'exemples.
    double attackWeight = usable.size() / (2.0 * attacks);
    double normalWeight = usable.size() / (2.0 * normals);

    float[] weights = new float[UrlFeatures.BUCKETS];
    double bias = 0;
    var order = new ArrayList<>(usable);
    var random = new Random(SEED);
    int step = 0;
    for (int epoch = 0; epoch < EPOCHS; epoch++) {
      java.util.Collections.shuffle(order, random);
      for (Sample sample : order) {
        double value = 1 / Math.sqrt(sample.features().size());
        double sum = bias;
        for (int bucket : sample.features()) {
          sum += weights[bucket] * value;
        }
        double predicted = UrlModel.sigmoid(sum);
        double target = sample.attack() ? 1 : 0;
        double weight = sample.attack() ? attackWeight : normalWeight;
        double gradient = (predicted - target) * weight;
        double rate = LEARNING_RATE / (1 + 1e-4 * step++);
        for (int bucket : sample.features()) {
          weights[bucket] -= (float) (rate * (gradient * value + L2 * weights[bucket]));
        }
        bias -= rate * gradient;
      }
    }
    return new UrlModel(weights, (float) bias);
  }

  /** Écrit le modèle au format lu par {@link UrlModel}. */
  public static void write(UrlModel model, Writer writer, String provenance) throws IOException {
    var out = new StringBuilder();
    out.append("# Modèle appris de reconnaissance de charges utiles dans une requête web (S4).\n");
    out.append("# Régression logistique sur les suites de ")
        .append(UrlFeatures.NGRAM)
        .append(" caractères, ")
        .append(UrlFeatures.BUCKETS)
        .append(" cases.\n");
    out.append("# Produit par UrlModelTrainer ; ne pas modifier à la main.\n");
    out.append("# ").append(provenance).append('\n');
    out.append("cases ").append(UrlFeatures.BUCKETS).append('\n');
    out.append("suite ").append(UrlFeatures.NGRAM).append('\n');
    out.append(String.format(Locale.ROOT, "biais %.6f%n", model.bias()).replace("\r\n", "\n"));
    float[] weights = model.weights();
    for (int bucket = 0; bucket < weights.length; bucket++) {
      if (weights[bucket] != 0) {
        out.append(
            String.format(Locale.ROOT, "poids %d %.6f%n", bucket, weights[bucket])
                .replace("\r\n", "\n"));
      }
    }
    writer.write(out.toString());
  }

  /** Réapprentissage en ligne de commande : normales.txt attaques.txt sortie.txt */
  public static void main(String[] args) throws IOException {
    if (args.length != 3) {
      System.err.println(
          "Usage : UrlModelTrainer <requetes-normales.txt> <requetes-attaques.txt> <sortie.txt>");
      System.exit(2);
      return;
    }
    var samples = new ArrayList<Sample>();
    for (String line : Files.readAllLines(Path.of(args[0]), StandardCharsets.UTF_8)) {
      if (!line.isBlank() && !line.startsWith("#")) {
        samples.add(sample(line.strip(), false));
      }
    }
    for (String line : Files.readAllLines(Path.of(args[1]), StandardCharsets.UTF_8)) {
      if (line.isBlank() || line.startsWith("#")) {
        continue;
      }
      // Le corpus d'attaques porte la famille en tête de ligne, séparée par une tabulation.
      int tab = line.indexOf('\t');
      samples.add(sample(tab < 0 ? line.strip() : line.substring(tab + 1).strip(), true));
    }
    UrlModel model = train(samples);
    try (BufferedWriter writer =
        Files.newBufferedWriter(Path.of(args[2]), StandardCharsets.UTF_8)) {
      write(model, writer, "Appris sur " + samples.size() + " requêtes.");
    }
    System.out.println("Modèle écrit dans " + args[2] + " (" + samples.size() + " requêtes)");
  }
}

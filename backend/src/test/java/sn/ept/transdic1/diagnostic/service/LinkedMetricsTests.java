package sn.ept.transdic1.diagnostic.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sn.ept.transdic1.diagnostic.repository.DiagnosticRepository.SeriesPoint;

/**
 * Indicateurs liés à une alerte (M11) : l'alignement des séries, la correction pour le nombre
 * d'essais, et les voisins. Les séries sont écrites à la main.
 */
class LinkedMetricsTests {
  private static final UUID OWN = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID NEIGHBOUR = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final Instant OPENED = Instant.parse("2026-11-23T10:00:00Z");
  private static final LinkedMetrics.Windows WINDOWS =
      new LinkedMetrics.Windows(
          OPENED.minus(Duration.ofMinutes(15)), OPENED.plus(Duration.ofMinutes(5)));
  private static final LinkedMetrics.Settings SETTINGS = LinkedMetrics.Settings.defaults();

  /** Une série de {@code minutes} points, la plus ancienne d'abord, finissant à l'ouverture. */
  private static List<SeriesPoint> series(
      UUID equipmentId,
      String name,
      String key,
      int minutes,
      java.util.function.IntToDoubleFunction value) {
    var points = new ArrayList<SeriesPoint>();
    for (int i = 0; i < minutes; i++) {
      points.add(
          new SeriesPoint(
              equipmentId,
              name,
              key,
              "%",
              OPENED.minus(Duration.ofMinutes(minutes - 1L - i)),
              value.applyAsDouble(i)));
    }
    return points;
  }

  private static List<SeriesPoint> concat(List<SeriesPoint>... parts) {
    var all = new ArrayList<SeriesPoint>();
    for (var part : parts) {
      all.addAll(part);
    }
    return all;
  }

  @Test
  @DisplayName("Un indicateur qui suit la mesure en alerte est retenu, avec sa corrélation")
  void ametricFollowingThealertingOneIsKept() {
    var random = new Random(5L);
    var reference = series(OWN, "Serveur", "cpu.usage", 60, i -> 40 + random.nextGaussian() * 8);
    var follower =
        series(OWN, "Serveur", "probe.latency", 60, i -> reference.get(i).value() / 3 + 1);
    var unrelated = series(OWN, "Serveur", "disk.usage", 60, i -> 70 + random.nextGaussian());

    var outcome =
        LinkedMetrics.correlate(
            concat(reference, follower, unrelated), OWN, "cpu.usage", WINDOWS, SETTINGS);

    assertThat(outcome.linked()).hasSize(1);
    assertThat(outcome.linked().get(0).key()).isEqualTo("probe.latency");
    assertThat(outcome.linked().get(0).correlation()).isEqualTo(1.0);
    // Soixante relevés font cinquante-neuf variations : c'est elles qu'on corrèle.
    assertThat(outcome.linked().get(0).points()).isEqualTo(59);
    // Deux séries ont été éprouvées : c'est par deux que le seuil de hasard a été divisé.
    assertThat(outcome.tested()).isEqualTo(2);
  }

  @Test
  @DisplayName("Ce que la méthode apporte : un voisin de la topologie, pas seulement la machine")
  void whatTheMethodAddsAneighbourNotJustTheMachine() {
    var random = new Random(9L);
    var reference = series(OWN, "Serveur", "net.in", 60, i -> 500 + random.nextGaussian() * 60);
    var onNeighbour =
        series(NEIGHBOUR, "Commutateur amont", "net.out", 60, i -> reference.get(i).value() * 1.02);

    var outcome =
        LinkedMetrics.correlate(concat(reference, onNeighbour), OWN, "net.in", WINDOWS, SETTINGS);

    assertThat(outcome.linked()).hasSize(1);
    var linked = outcome.linked().get(0);
    assertThat(linked.equipmentId()).isEqualTo(NEIGHBOUR);
    assertThat(linked.equipmentName()).isEqualTo("Commutateur amont");
    assertThat(linked.key()).isEqualTo("net.out");
  }

  @Test
  @DisplayName("La correction pour le nombre d'essais écarte les corrélations de hasard")
  void thecorrectionForTheNumberOftriesDiscardsChanceCorrelations() {
    // Trente indicateurs de bruit pur. Au seuil habituel de 5 %, on en déclarerait un ou deux
    // « liés » à chaque consultation ; c'est exactement ce qu'un outil de diagnostic ne doit pas
    // faire. La correction les ramène à zéro.
    var random = new Random(77L);
    var points = new ArrayList<SeriesPoint>();
    points.addAll(series(OWN, "Serveur", "cpu.usage", 40, i -> random.nextGaussian()));
    for (int k = 0; k < 30; k++) {
      points.addAll(series(OWN, "Serveur", "bruit." + k, 40, i -> random.nextGaussian()));
    }

    var outcome = LinkedMetrics.correlate(points, OWN, "cpu.usage", WINDOWS, SETTINGS);

    assertThat(outcome.tested()).isEqualTo(30);
    assertThat(outcome.linked()).isEmpty();
  }

  @Test
  @DisplayName("Une corrélation vraie mais faible n'encombre pas l'écran")
  void atrueButWeakCorrelationDoesNotCluttertheScreen() {
    // Deux cents points et un lien ténu : indiscutable statistiquement, inutilisable en pratique.
    var random = new Random(3L);
    var reference = series(OWN, "Serveur", "cpu.usage", 200, i -> random.nextGaussian());
    var weak =
        series(
            OWN,
            "Serveur",
            "memory.usage",
            200,
            i -> reference.get(i).value() * 0.3 + random.nextGaussian() * 2);

    var outcome =
        LinkedMetrics.correlate(concat(reference, weak), OWN, "cpu.usage", WINDOWS, SETTINGS);

    assertThat(outcome.tested()).isEqualTo(1);
    assertThat(outcome.linked()).isEmpty();
  }

  @Test
  @DisplayName("Sans série de référence, rien n'est corrélé")
  void withoutAreferenceSeriesNothingIsCorrelated() {
    var other = series(OWN, "Serveur", "memory.usage", 60, i -> i);

    // Une perte de collecte ou un événement SNMP ne porte sur aucun indicateur.
    assertThat(LinkedMetrics.correlate(other, OWN, null, WINDOWS, SETTINGS).linked()).isEmpty();
    assertThat(LinkedMetrics.correlate(other, OWN, "cpu.usage", WINDOWS, SETTINGS).linked())
        .isEmpty();
    assertThat(LinkedMetrics.correlate(List.of(), OWN, "cpu.usage", WINDOWS, SETTINGS).tested())
        .isZero();
  }

  @Test
  @DisplayName("Les séries sont alignées sur leurs instants communs, pas sur leur longueur")
  void theseriesAreAlignedOnTheirCommonInstantsNotTheirLength() {
    // La référence est relevée chaque minute, l'autre une minute sur deux : seuls les instants
    // communs comptent, et les aligner par position mélangerait des moments différents. Deux
    // cadences différentes restent donc comparables, ce qui est le cas courant d'un parc réel.
    var random = new Random(61L);
    var levels = new double[60];
    for (int i = 0; i < levels.length; i++) {
      levels[i] = 40 + random.nextGaussian() * 10;
    }
    var reference = series(OWN, "Serveur", "cpu.usage", 60, i -> levels[i]);
    var sparse = new ArrayList<SeriesPoint>();
    for (int i = 0; i < 60; i += 2) {
      sparse.add(
          new SeriesPoint(
              OWN,
              "Serveur",
              "memory.usage",
              "%",
              OPENED.minus(Duration.ofMinutes(59L - i)),
              levels[i] * 2));
    }

    var outcome =
        LinkedMetrics.correlate(concat(reference, sparse), OWN, "cpu.usage", WINDOWS, SETTINGS);

    assertThat(outcome.linked()).hasSize(1);
    // Trente relevés communs, donc vingt-neuf variations, prises sur des écarts de deux minutes.
    assertThat(outcome.linked().get(0).points()).isEqualTo(29);
    assertThat(outcome.linked().get(0).correlation()).isEqualTo(1.0);
  }

  @Test
  @DisplayName("Une rampe parfaitement régulière n'a aucune variation à corréler")
  void aperfectlyRegularRampHasNovariationToCorrelate() {
    // Conséquence directe du choix de corréler les variations : une série qui monte d'exactement
    // un point par minute a des variations constantes, donc sans ordre. Elle n'est pas éprouvée.
    // Cela ne coûte rien en pratique — aucune mesure réelle ne monte à la perfection — et c'est le
    // prix de ne pas prendre deux dérives pour un lien.
    var reference = series(OWN, "Serveur", "cpu.usage", 60, i -> i);
    var other = series(OWN, "Serveur", "memory.usage", 60, i -> i * 3.0);

    var outcome =
        LinkedMetrics.correlate(concat(reference, other), OWN, "cpu.usage", WINDOWS, SETTINGS);

    assertThat(outcome.tested()).isZero();
    assertThat(outcome.linked()).isEmpty();
  }

  @Test
  @DisplayName("Un trou dans la collecte ne fabrique pas une variation géante")
  void agapInCollectionDoesNotManufactureAgiantVariation() {
    // Trente minutes sans mesure au milieu : la variation qui enjambe le trou est écartée, parce
    // qu'elle vaudrait trente fois celle d'une minute et écraserait le classement des rangs.
    var random = new Random(67L);
    var points = new ArrayList<SeriesPoint>();
    for (int i = 0; i < 80; i++) {
      if (i >= 30 && i < 60) {
        continue;
      }
      double value = 40 + random.nextGaussian() * 10;
      Instant at = OPENED.minus(Duration.ofMinutes(79L - i));
      points.add(new SeriesPoint(OWN, "Serveur", "cpu.usage", "%", at, value));
      points.add(new SeriesPoint(OWN, "Serveur", "memory.usage", "%", at, value * 2));
    }

    var outcome = LinkedMetrics.correlate(points, OWN, "cpu.usage", WINDOWS, SETTINGS);

    assertThat(outcome.linked()).hasSize(1);
    // Cinquante relevés, deux segments : 29 + 19 variations, celle du trou en moins.
    assertThat(outcome.linked().get(0).points()).isEqualTo(48);
  }

  @Test
  @DisplayName("Trop peu d'instants communs : l'indicateur n'est pas même éprouvé")
  void toofewCommonInstantsTheMetricIsNotEvenTested() {
    var reference = series(OWN, "Serveur", "cpu.usage", 60, i -> i);
    var tooShort = series(OWN, "Serveur", "memory.usage", 5, i -> i);

    var outcome =
        LinkedMetrics.correlate(concat(reference, tooShort), OWN, "cpu.usage", WINDOWS, SETTINGS);

    assertThat(outcome.tested()).isZero();
    assertThat(outcome.linked()).isEmpty();
  }

  @Test
  @DisplayName("Le avant et le pendant suivent les bornes données, pas la série entière")
  void thebeforeAndDuringFollowTheGivenBounds() {
    var random = new Random(71L);
    var reference = series(OWN, "Serveur", "cpu.usage", 60, i -> 40 + random.nextGaussian() * 10);
    // 20 à partir de l'instant qui ouvre le « pendant », 10 avant.
    var stepped = series(OWN, "Serveur", "memory.usage", 60, i -> i >= 60 - 15 - 1 ? 20 : 10);

    var outcome =
        LinkedMetrics.correlate(
            concat(reference, stepped),
            OWN,
            "cpu.usage",
            WINDOWS,
            // Le seuil de corrélation est abaissé ici : ce test ne juge que les bornes du avant et
            // du pendant, pas la force du lien.
            new LinkedMetrics.Settings(10, 0.0, 1.0, 5, Duration.ofMinutes(5)));

    assertThat(outcome.linked()).hasSize(1);
    assertThat(outcome.linked().get(0).before()).isEqualTo(10.0);
    assertThat(outcome.linked().get(0).during()).isEqualTo(20.0);
  }

  @Test
  @DisplayName("Les liens les plus forts passent devant, et le nombre affiché est borné")
  void thestrongestLinksComeFirstAndTheNumberShownIsBounded() {
    var random = new Random(73L);
    var levels = new double[60];
    for (int i = 0; i < levels.length; i++) {
      levels[i] = 40 + random.nextGaussian() * 10;
    }
    var reference = series(OWN, "Serveur", "cpu.usage", 60, i -> levels[i]);
    var points = new ArrayList<SeriesPoint>(reference);
    // Huit suiveurs parfaits : plus que les cinq que l'écran accepte.
    for (int k = 0; k < 8; k++) {
      final int factor = k + 2;
      points.addAll(series(OWN, "Serveur", "suiveur." + k, 60, i -> levels[i] * factor));
    }

    var outcome = LinkedMetrics.correlate(points, OWN, "cpu.usage", WINDOWS, SETTINGS);

    assertThat(outcome.tested()).isEqualTo(8);
    assertThat(outcome.linked()).hasSize(SETTINGS.maxShown());
    assertThat(outcome.linked()).extracting(LinkedMetrics.Linked::correlation).containsOnly(1.0);
  }

  @Test
  @DisplayName("Un lien inverse est retenu comme les autres")
  void aninverseLinkIsKeptLikeTheOthers() {
    var random = new Random(79L);
    var levels = new double[60];
    for (int i = 0; i < levels.length; i++) {
      levels[i] = 400 + random.nextGaussian() * 80;
    }
    var reference = series(OWN, "Serveur", "net.in", 60, i -> levels[i]);
    var opposite = series(OWN, "Serveur", "net.errors", 60, i -> 1_000 - levels[i]);

    var outcome =
        LinkedMetrics.correlate(concat(reference, opposite), OWN, "net.in", WINDOWS, SETTINGS);

    assertThat(outcome.linked()).hasSize(1);
    assertThat(outcome.linked().get(0).correlation()).isEqualTo(-1.0);
  }
}

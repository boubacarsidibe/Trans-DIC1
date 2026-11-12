package sn.ept.transdic1.flow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sn.ept.transdic1.common.threat.NetworkScanDetected;

/**
 * Reconnaissance des balayages et des inondations (S6) : ce qui doit être signalé, et surtout ce
 * qui ne doit pas l'être. Les faux positifs d'une détection réseau coûtent plus cher que les oublis
 * : une sauvegarde nocturne prise pour une attaque fait perdre confiance à l'équipe.
 */
class ScanDetectorTests {
  private static final UUID EQUIPMENT = UUID.randomUUID();
  private static final ScanDetector.Thresholds THRESHOLDS = ScanDetector.Thresholds.defaults();

  // ------------------------------------------------------------------------------- entropie

  @Test
  @DisplayName("L'entropie dit si la répartition est plate ou creusée")
  void entropySaysWhetherTheSpreadIsFlatOrHollow() {
    // Parfaitement plate : le maximum.
    assertThat(FlowEntropy.normalised(List.of(1L, 1L, 1L, 1L))).isEqualTo(1, within(0.001));
    // Presque tout au même endroit : proche de zéro.
    assertThat(FlowEntropy.normalised(List.of(1_000L, 1L, 1L, 1L))).isLessThan(0.2);
    // Une seule catégorie, ou aucune : rien d'éparpillé.
    assertThat(FlowEntropy.normalised(List.of(42L))).isZero();
    assertThat(FlowEntropy.normalised(List.of())).isZero();
    assertThat(FlowEntropy.normalised(null)).isZero();
  }

  // --------------------------------------------------------------------- balayage d'adresses

  private static ScanDetector.SourceView scanner(int targets, long bytesPerFlow) {
    var destinations = new HashMap<String, Long>();
    for (int i = 0; i < targets; i++) {
      destinations.put("10.1." + (i / 250) + "." + (i % 250), 1L);
    }
    return new ScanDetector.SourceView(
        "10.9.9.9", destinations, Map.of(), targets, bytesPerFlow * targets, false);
  }

  @Test
  @DisplayName("Une source qui touche deux cents machines en échanges minuscules est un balayage")
  void aSourceTouchingTwoHundredMachinesInTinyExchangesIsAScan() {
    List<NetworkScanDetected> findings =
        ScanDetector.examine(EQUIPMENT, List.of(scanner(240, 60)), List.of(), THRESHOLDS);

    assertThat(findings).hasSize(1);
    NetworkScanDetected finding = findings.get(0);
    assertThat(finding.kind()).isEqualTo(NetworkScanDetected.Kind.ADDRESS_SCAN);
    assertThat(finding.address()).isEqualTo("10.9.9.9");
    assertThat(finding.count()).isEqualTo(240);
    assertThat(finding.entropy()).isEqualTo(1, within(0.001));
  }

  @Test
  @DisplayName("Un serveur de sauvegarde qui parle à beaucoup de machines n'en est pas un")
  void aBackupServerTalkingToManyMachinesIsNotOne() {
    // Beaucoup de destinations, mais très inégalement : presque tout va vers deux machines.
    var destinations = new HashMap<String, Long>();
    destinations.put("10.1.0.1", 50_000L);
    destinations.put("10.1.0.2", 40_000L);
    for (int i = 0; i < 250; i++) {
      destinations.put("10.2.0." + i, 1L);
    }
    var source =
        new ScanDetector.SourceView("10.5.5.5", destinations, Map.of(), 90_250, 9_000, false);

    assertThat(ScanDetector.examine(EQUIPMENT, List.of(source), List.of(), THRESHOLDS)).isEmpty();
  }

  @Test
  @DisplayName("Beaucoup de destinations mais de vrais échanges : ce n'est pas du sondage")
  void manyDestinationsButRealExchangesIsNotProbing() {
    // 240 machines, répartition plate, mais 50 Kio par flux : un déploiement, pas un scan.
    assertThat(
            ScanDetector.examine(EQUIPMENT, List.of(scanner(240, 50_000)), List.of(), THRESHOLDS))
        .isEmpty();
  }

  @Test
  @DisplayName("Sous le seuil, rien n'est signalé")
  void belowTheThresholdNothingIsReported() {
    assertThat(ScanDetector.examine(EQUIPMENT, List.of(scanner(150, 60)), List.of(), THRESHOLDS))
        .isEmpty();
  }

  @Test
  @DisplayName("Un comptage plafonné est annoncé comme tel")
  void acappedCountIsAnnouncedAsSuch() {
    var capped =
        new ScanDetector.SourceView(
            "10.9.9.9", scanner(240, 60).destinations(), Map.of(), 10_000, 300_000, true);

    List<NetworkScanDetected> findings =
        ScanDetector.examine(EQUIPMENT, List.of(capped), List.of(), THRESHOLDS);

    assertThat(findings).hasSize(1);
    assertThat(findings.get(0).countSaturated()).isTrue();
  }

  // ------------------------------------------------------------------------ balayage de ports

  @Test
  @DisplayName("Une source qui frappe cent ports d'une même machine est un balayage de ports")
  void aSourceHittingAHundredPortsOfOneMachineIsAPortScan() {
    var source =
        new ScanDetector.SourceView(
            "10.9.9.9",
            Map.of("10.1.0.1", 120L),
            Map.of("10.1.0.1", 120, "10.1.0.2", 2),
            122,
            4_000,
            false);

    List<NetworkScanDetected> findings =
        ScanDetector.examine(EQUIPMENT, List.of(source), List.of(), THRESHOLDS);

    assertThat(findings).hasSize(1);
    assertThat(findings.get(0).kind()).isEqualTo(NetworkScanDetected.Kind.PORT_SCAN);
    assertThat(findings.get(0).count()).isEqualTo(120);
  }

  @Test
  @DisplayName("Quelques ports sur une machine ne disent rien")
  void aFewPortsOnOneMachineSayNothing() {
    var source =
        new ScanDetector.SourceView(
            "10.9.9.9", Map.of("10.1.0.1", 8L), Map.of("10.1.0.1", 8), 8, 900_000, false);

    assertThat(ScanDetector.examine(EQUIPMENT, List.of(source), List.of(), THRESHOLDS)).isEmpty();
  }

  // ------------------------------------------------------------------------------ inondation

  @Test
  @DisplayName("Une destination assaillie par deux cents sources égales est une inondation")
  void aDestinationAssailedByTwoHundredEqualSourcesIsAFlood() {
    var sources = new HashMap<String, Long>();
    for (int i = 0; i < 260; i++) {
      sources.put("203.0." + (i / 250) + "." + (i % 250), 3L);
    }
    var destination = new ScanDetector.DestinationView("10.1.0.50", sources, 780, false);

    List<NetworkScanDetected> findings =
        ScanDetector.examine(EQUIPMENT, List.of(), List.of(destination), THRESHOLDS);

    assertThat(findings).hasSize(1);
    assertThat(findings.get(0).kind()).isEqualTo(NetworkScanDetected.Kind.FLOOD);
    assertThat(findings.get(0).address()).isEqualTo("10.1.0.50");
    assertThat(findings.get(0).flows()).isEqualTo(780);
  }

  @Test
  @DisplayName("Un serveur web fréquenté n'est pas inondé")
  void abusyWebServerIsNotFlooded() {
    // Beaucoup de clients, mais quelques-uns font l'essentiel du trafic : un usage ordinaire.
    var sources = new HashMap<String, Long>();
    sources.put("10.1.1.1", 20_000L);
    sources.put("10.1.1.2", 15_000L);
    for (int i = 0; i < 250; i++) {
      sources.put("10.3.0." + i, 1L);
    }
    var destination = new ScanDetector.DestinationView("10.1.0.80", sources, 35_250, false);

    assertThat(ScanDetector.examine(EQUIPMENT, List.of(), List.of(destination), THRESHOLDS))
        .isEmpty();
  }
}

package sn.ept.transdic1.alerting.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Écart aux semblables : référence du groupe, sens unique et marge de retour. */
class PeerOutlierTests {
  private static final double SENSITIVITY = 4.0;

  private static PeerOutlier.Group group(List<Double> values) {
    return PeerOutlier.group(values, 0.10, 5.0).orElseThrow();
  }

  @Test
  void unGroupeTropPetitNeDonneAucuneReference() {
    assertThat(PeerOutlier.group(List.of(10.0, 12.0, 11.0), 0.10, 5.0)).isEmpty();
    assertThat(PeerOutlier.group(List.of(10.0, 12.0, 11.0, 13.0), 0.10, 5.0)).isPresent();
  }

  @Test
  void celuiQuiSeDemarqueEstRepereLesAutresNon() {
    var reference = group(List.of(10.0, 11.0, 9.0, 12.0, 10.0, 80.0));

    assertThat(PeerOutlier.standsOut(reference, 80.0, SENSITIVITY)).isTrue();
    assertThat(PeerOutlier.standsOut(reference, 12.0, SENSITIVITY)).isFalse();
    assertThat(PeerOutlier.standsOut(reference, 10.0, SENSITIVITY)).isFalse();
  }

  @Test
  void unSeulIntrusNeDeplacePasLaReference() {
    // La médiane résiste à l'intrus, contrairement à une moyenne qui monterait à 21.
    var reference = group(List.of(10.0, 11.0, 9.0, 12.0, 10.0, 80.0));

    assertThat(reference.median()).isBetween(10.0, 11.0);
    assertThat(reference.size()).isEqualTo(6);
  }

  @Test
  void unePetiteAvanceNeSuffitPasDansUnGroupeHomogene() {
    // Groupe parfaitement homogène : sans plancher, 21 % serait à une infinité d'écarts.
    var reference = group(List.of(20.0, 20.0, 20.0, 20.0, 20.0));

    assertThat(PeerOutlier.standsOut(reference, 21.0, SENSITIVITY)).isFalse();
    assertThat(PeerOutlier.standsOut(reference, 45.0, SENSITIVITY)).isTrue();
  }

  @Test
  void etreEnDessousDesSemblablesNEstPasUnProbleme() {
    var reference = group(List.of(70.0, 72.0, 68.0, 71.0, 69.0));

    assertThat(PeerOutlier.standsOut(reference, 1.0, SENSITIVITY)).isFalse();
    assertThat(reference.score(1.0)).isNegative();
  }

  @Test
  void leRetourDansLeRangAUneMarge() {
    var reference = group(List.of(10.0, 11.0, 9.0, 12.0, 10.0));
    double atThreshold =
        reference.median() + SENSITIVITY * Math.max(reference.spread(), reference.floor());

    assertThat(PeerOutlier.isBackInLine(reference, atThreshold, SENSITIVITY)).isFalse();
    assertThat(PeerOutlier.isBackInLine(reference, reference.median() + 1, SENSITIVITY)).isTrue();
  }
}

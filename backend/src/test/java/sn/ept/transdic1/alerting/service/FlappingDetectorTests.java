package sn.ept.transdic1.alerting.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Décision « alerte instable » : seuil d'ouvertures et marge de retour au calme. */
class FlappingDetectorTests {
  @Test
  void auSeuilLAlerteDevientInstable() {
    assertThat(FlappingDetector.isFlapping(3, 4)).isFalse();
    assertThat(FlappingDetector.isFlapping(4, 4)).isTrue();
    assertThat(FlappingDetector.isFlapping(9, 4)).isTrue();
  }

  @Test
  void leRetourAuCalmeExigeUneMarge() {
    // Sous le seuil mais encore haut : l'alerte reste instable, sinon elle alternerait sans fin.
    assertThat(FlappingDetector.isCalmAgain(3, 4)).isFalse();
    assertThat(FlappingDetector.isCalmAgain(2, 4)).isTrue();
    assertThat(FlappingDetector.isCalmAgain(1, 4)).isTrue();
  }

  @Test
  void leSeuilDeCalmeResteAtteignableMemePourUnSeuilBas() {
    // Avec un seuil de 2, la moitié vaut 1 : une seule ouverture suffit à redevenir normale.
    assertThat(FlappingDetector.calmThreshold(2)).isEqualTo(1);
    assertThat(FlappingDetector.calmThreshold(3)).isEqualTo(1);
    assertThat(FlappingDetector.calmThreshold(10)).isEqualTo(5);
    assertThat(FlappingDetector.isCalmAgain(1, 2)).isTrue();
    assertThat(FlappingDetector.isCalmAgain(2, 2)).isFalse();
  }

  @Test
  void lExplicationDitCeQuiSePasseEtCeQueCaChange() {
    assertThat(FlappingDetector.explain(5, 60))
        .isEqualTo(
            "Alerte instable : 5 ouvertures en 60 min, notifications suspendues jusqu'au retour au"
                + " calme");
  }
}

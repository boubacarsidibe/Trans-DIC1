package sn.ept.transdic1.diagnostic.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Ce qui a bougé en même temps que l'alerte : variation, plancher et seuil d'intérêt. */
class MetricMovementTests {
  @Test
  void laVariationEstLeChangementRelatifEnPourcentage() {
    assertThat(MetricMovement.changePercent(20.0, 30.0)).isEqualTo(50.0);
    assertThat(MetricMovement.changePercent(40.0, 20.0)).isEqualTo(-50.0);
    assertThat(MetricMovement.changePercent(10.0, 10.0)).isZero();
  }

  @Test
  void deValeursQuasiNullesNeFontPasUneHausseSpectaculaire() {
    // 0,01 → 0,05 : quadruplé sur le papier, insignifiant en vrai. Le plancher le ramène à sa
    // place.
    Double change = MetricMovement.changePercent(0.01, 0.05);
    assertThat(change).isNotNull();
    assertThat(Math.abs(change)).isLessThan(MetricMovement.MIN_CHANGE_PERCENT);
  }

  @Test
  void unIndicateurSansValeurNEstPasCompare() {
    assertThat(MetricMovement.changePercent(null, 10.0)).isNull();
    assertThat(MetricMovement.changePercent(10.0, null)).isNull();
    assertThat(MetricMovement.isNotable(null, 10, 10)).isFalse();
  }

  @Test
  void unPetitChangementOuTropPeuDePointsNeSAffichePas() {
    assertThat(MetricMovement.isNotable(15.0, 10, 10)).isFalse();
    assertThat(MetricMovement.isNotable(60.0, 2, 10)).isFalse();
    assertThat(MetricMovement.isNotable(60.0, 10, 2)).isFalse();
    assertThat(MetricMovement.isNotable(60.0, 3, 3)).isTrue();
    assertThat(MetricMovement.isNotable(-60.0, 3, 3)).isTrue();
  }
}

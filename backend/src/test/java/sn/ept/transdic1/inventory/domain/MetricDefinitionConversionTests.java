package sn.ept.transdic1.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Valeur dérivée d'un indicateur SNMP : brute × scale + offset, puis 100 - x si demandé. */
class MetricDefinitionConversionTests {

  private static MetricDefinition definition() {
    return new MetricDefinition(null, "Indicateur", "cle", ValueType.DECIMAL);
  }

  @Test
  void withoutConversionTheRawValueIsKeptAsIs() {
    var raw = new BigDecimal("123456.789");
    assertThat(definition().derive(raw)).isSameAs(raw);
  }

  @Test
  void scaleMultipliesTheRawValue() {
    var d = definition();
    d.setScale(new BigDecimal("0.1"));
    // mtxrHlTemperature est en dixièmes de degré.
    assertThat(d.derive(new BigDecimal("415"))).isEqualByComparingTo("41.5");
  }

  @Test
  void offsetIsAddedAfterTheScale() {
    var d = definition();
    d.setScale(new BigDecimal("2"));
    d.setValueOffset(new BigDecimal("-10"));
    assertThat(d.derive(new BigDecimal("30"))).isEqualByComparingTo("50");
  }

  @Test
  void invertPercentTurnsAnIdleRateIntoALoad() {
    var d = definition();
    d.setInvertPercent(true);
    // ssCpuIdle = 93 % -> charge = 7 %.
    assertThat(d.derive(new BigDecimal("93"))).isEqualByComparingTo("7");
  }

  @Test
  void invertPercentAppliesAfterScaleAndOffset() {
    var d = definition();
    d.setScale(new BigDecimal("0.5"));
    d.setValueOffset(new BigDecimal("10"));
    d.setInvertPercent(true);
    // 100 - (40 × 0,5 + 10) = 70
    assertThat(d.derive(new BigDecimal("40"))).isEqualByComparingTo("70");
  }

  @Test
  void derivedValuesAreRoundedToTheStoredPrecision() {
    var d = definition();
    d.setScale(new BigDecimal("0.01"));
    // TimeTicks (centièmes de seconde) -> secondes, 4 décimales comme la colonne metric_value.
    assertThat(d.derive(new BigDecimal("12345"))).isEqualByComparingTo("123.45");
    d.setScale(new BigDecimal("0.000001"));
    assertThat(d.derive(new BigDecimal("1")).scale()).isLessThanOrEqualTo(4);
  }
}

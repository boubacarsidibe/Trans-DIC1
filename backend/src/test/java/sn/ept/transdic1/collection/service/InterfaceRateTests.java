package sn.ept.transdic1.collection.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Débit d'une interface : écart de compteurs, débordement, redémarrage et bornes de temps. */
class InterfaceRateTests {
  private static final Instant BEFORE = Instant.parse("2026-11-11T08:00:00Z");
  private static final Instant AFTER = BEFORE.plus(Duration.ofSeconds(60));

  private static BigDecimal rate(long previous, long current) {
    return InterfaceRate.perSecond(
        BigInteger.valueOf(previous), BEFORE, BigInteger.valueOf(current), AFTER);
  }

  @Test
  void leDebitEstLEcartDesCompteursDiviseParLeTemps() {
    // 600 000 octets en 60 secondes.
    assertThat(rate(1_000_000, 1_600_000)).isEqualByComparingTo("10000");
  }

  @Test
  void unCompteurImmobileDonneUnDebitNul() {
    assertThat(rate(500, 500)).isEqualByComparingTo("0");
  }

  @Test
  void unPremierPassageNeDonnePasDeDebit() {
    assertThat(InterfaceRate.perSecond(null, null, BigInteger.TEN, AFTER)).isNull();
    assertThat(InterfaceRate.perSecond(BigInteger.ONE, null, BigInteger.TEN, AFTER)).isNull();
    assertThat(InterfaceRate.perSecond(BigInteger.ONE, BEFORE, null, AFTER)).isNull();
  }

  @Test
  void unEcartDeTempsTropCourtOuTropLongNeDonneRien() {
    assertThat(
            InterfaceRate.perSecond(
                BigInteger.ZERO, BEFORE, BigInteger.valueOf(100), BEFORE.plusSeconds(5)))
        .isNull();
    assertThat(
            InterfaceRate.perSecond(
                BigInteger.ZERO, BEFORE, BigInteger.valueOf(100), BEFORE.plus(Duration.ofHours(3))))
        .isNull();
    // Dix secondes pile : la borne basse est acceptée.
    assertThat(
            InterfaceRate.perSecond(
                BigInteger.ZERO, BEFORE, BigInteger.valueOf(100), BEFORE.plusSeconds(10)))
        .isEqualByComparingTo("10");
  }

  @Test
  void unCompteurTrenteDeuxBitsQuiDebordeEstRattrape() {
    // 2^32 - 1000 puis 2000 : 3000 octets ont circulé, pas un retour en arrière.
    BigInteger previous = InterfaceRate.WIDTH_32.subtract(BigInteger.valueOf(1000));
    assertThat(InterfaceRate.delta(previous, BigInteger.valueOf(2000)))
        .isEqualTo(BigInteger.valueOf(3000));
  }

  @Test
  void unRedemarrageSurUnCompteurSoixanteQuatreBitsNInventePasDePic() {
    // Un compteur 64 bits ne déborde pas en pratique (des dizaines d'années à 100 Gb/s) : s'il
    // recule, l'équipement a redémarré. Mieux vaut aucune valeur qu'un pic de plusieurs téraoctets.
    BigInteger previous = InterfaceRate.WIDTH_32.add(BigInteger.valueOf(5_000_000_000L));
    assertThat(InterfaceRate.delta(previous, BigInteger.valueOf(1000))).isNull();
    assertThat(InterfaceRate.perSecond(previous, BEFORE, BigInteger.valueOf(1000), AFTER)).isNull();
  }

  @Test
  void loccupationDuLienCompareLeDebitALaVitesseAnnoncee() {
    // 12,5 Mo/s = 100 Mb/s, soit la moitié d'un lien à 200 Mb/s.
    assertThat(InterfaceRate.usagePercent(new BigDecimal("12500000"), 200_000_000L))
        .isEqualByComparingTo("50.00");
    assertThat(InterfaceRate.usagePercent(new BigDecimal("1000"), null)).isNull();
    assertThat(InterfaceRate.usagePercent(new BigDecimal("1000"), 0L)).isNull();
    assertThat(InterfaceRate.usagePercent(null, 100L)).isNull();
  }

  @Test
  void loccupationNeDepassePasCentPourCent() {
    // Vitesse mal annoncée par l'équipement : on plafonne au lieu d'afficher 400 %.
    assertThat(InterfaceRate.usagePercent(new BigDecimal("12500000"), 10_000_000L))
        .isEqualByComparingTo("100");
  }
}

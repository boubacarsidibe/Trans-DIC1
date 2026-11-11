package sn.ept.transdic1.security.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Code à usage unique : conformité au vecteur de test de la RFC 6238, tolérance d'horloge et refus
 * de tout le reste.
 */
class TotpTests {
  /** Secret du vecteur de test de la RFC 6238 : « 12345678901234567890 » en base32. */
  private static final String RFC_SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

  @Test
  void leCodeSuitLeVecteurDeTestDeLaNorme() {
    // RFC 6238, annexe B : à 59 s puis à 1 111 111 109 s, en SHA-1 et 8 chiffres, les codes
    // finissent par 94287082 et 07081804 ; sur 6 chiffres, ce sont les six derniers.
    assertThat(Totp.code(RFC_SECRET, Instant.ofEpochSecond(59))).isEqualTo("287082");
    assertThat(Totp.code(RFC_SECRET, Instant.ofEpochSecond(1_111_111_109L))).isEqualTo("081804");
    assertThat(Totp.code(RFC_SECRET, Instant.ofEpochSecond(1_234_567_890L))).isEqualTo("005924");
  }

  @Test
  void leCodeChangeToutesLesTrenteSecondes() {
    // Instant aligné sur un début de pas : 1 700 000 010 est un multiple de 30.
    Instant at = Instant.ofEpochSecond(1_700_000_010L);

    assertThat(Totp.code(RFC_SECRET, at)).isEqualTo(Totp.code(RFC_SECRET, at.plusSeconds(29)));
    assertThat(Totp.code(RFC_SECRET, at)).isNotEqualTo(Totp.code(RFC_SECRET, at.plusSeconds(60)));
  }

  @Test
  void uneHorlogeLegerementDecaleeEstAcceptee() {
    Instant at = Instant.ofEpochSecond(1_700_000_000L);
    String code = Totp.code(RFC_SECRET, at);

    assertThat(Totp.verify(RFC_SECRET, code, at)).isTrue();
    // Un pas d'écart de part et d'autre : le téléphone de l'opérateur n'est jamais à la seconde.
    assertThat(Totp.verify(RFC_SECRET, code, at.plus(Duration.ofSeconds(30)))).isTrue();
    assertThat(Totp.verify(RFC_SECRET, code, at.minus(Duration.ofSeconds(30)))).isTrue();
    // Deux pas : trop vieux.
    assertThat(Totp.verify(RFC_SECRET, code, at.plus(Duration.ofSeconds(90)))).isFalse();
  }

  @Test
  void lesEspacesDeSaisieSontTolerésMaisPasUnMauvaisCode() {
    Instant at = Instant.ofEpochSecond(1_700_000_000L);
    String code = Totp.code(RFC_SECRET, at);

    assertThat(Totp.verify(RFC_SECRET, code.substring(0, 3) + " " + code.substring(3), at))
        .isTrue();
    assertThat(Totp.verify(RFC_SECRET, "000000", at)).isFalse();
    assertThat(Totp.verify(RFC_SECRET, "12345", at)).isFalse();
    assertThat(Totp.verify(RFC_SECRET, "1234567", at)).isFalse();
    assertThat(Totp.verify(RFC_SECRET, null, at)).isFalse();
    assertThat(Totp.verify(null, code, at)).isFalse();
  }

  @Test
  void unSecretNeufEstUtilisableEtDifferentAChaqueFois() {
    String first = Totp.newSecret();
    String second = Totp.newSecret();

    assertThat(first).hasSize(32).isNotEqualTo(second).matches("[A-Z2-7]+");
    Instant at = Instant.now();
    assertThat(Totp.verify(first, Totp.code(first, at), at)).isTrue();
    assertThat(Totp.verify(second, Totp.code(first, at), at)).isFalse();
  }

  @Test
  void lAdresseOtpauthPorteCeQuAttendentLesApplications() {
    String uri = Totp.uri("Trans-DIC1", "awa noc", RFC_SECRET);

    assertThat(uri)
        .startsWith("otpauth://totp/Trans-DIC1:awa%20noc?secret=" + RFC_SECRET)
        .contains("issuer=Trans-DIC1")
        .contains("algorithm=SHA1")
        .contains("digits=6")
        .contains("period=30");
  }

  @Test
  void leBase32FaitLAllerRetour() {
    byte[] original = "12345678901234567890".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    assertThat(Totp.base32(original)).isEqualTo(RFC_SECRET);
    assertThat(Totp.decodeBase32(RFC_SECRET)).isEqualTo(original);
    // Les applications rendent parfois le secret en minuscules et par groupes.
    assertThat(Totp.decodeBase32("gezd gnbv gy3t qojq gezd gnbv gy3t qojq")).isEqualTo(original);
  }
}

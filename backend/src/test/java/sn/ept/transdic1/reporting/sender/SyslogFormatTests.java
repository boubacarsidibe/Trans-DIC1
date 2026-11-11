package sn.ept.transdic1.reporting.sender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sn.ept.transdic1.alerting.domain.Severity;

/**
 * Mise en forme du message syslog envoyé à un SIEM (S8). La forme exacte décide si le collecteur
 * sait lire le message : elle se vérifie caractère par caractère, sans réseau.
 */
class SyslogFormatTests {
  private static final Instant WHEN = Instant.parse("2026-11-11T19:45:03.250Z");

  @Test
  @DisplayName("La priorité combine l'origine et la gravité")
  void priorityCombinesFacilityAndSeverity() {
    // local4 (20) * 8 + critical (2)
    assertThat(message(20, Severity.CRITICAL)).startsWith("<162>1 ");
    // local4 * 8 + warning (4)
    assertThat(message(20, Severity.WARNING)).startsWith("<164>1 ");
    // local0 (16) * 8 + info (6)
    assertThat(message(16, Severity.INFO)).startsWith("<134>1 ");
  }

  @Test
  @DisplayName("Une sévérité inconnue ne dramatise pas")
  void anUnknownSeverityDoesNotDramatise() {
    // notice (5) : on ne sait pas de quoi il s'agit, on ne réveille personne.
    assertThat(Syslog.severity(null)).isEqualTo(5);
    assertThat(message(20, null)).startsWith("<165>1 ");
  }

  @Test
  @DisplayName("L'en-tête suit la norme, horodatage UTC compris")
  void theHeaderFollowsTheStandard() {
    String message =
        Syslog.format(
            20, Severity.WARNING, WHEN, "supervision.ept.sn", "ALERT_OPENED", "Sujet", "Corps");

    assertThat(message)
        .isEqualTo(
            "<164>1 2026-11-11T19:45:03.250Z supervision.ept.sn trans-dic1 - ALERT_OPENED -"
                + " Sujet — Corps");
  }

  @Test
  @DisplayName("Un champ d'en-tête vide devient la valeur nulle de la norme")
  void anEmptyHeaderFieldBecomesTheStandardNilValue() {
    String message = Syslog.format(20, Severity.INFO, WHEN, "  ", null, "Sujet", null);

    assertThat(message).contains(" - trans-dic1 - - - Sujet");
  }

  @Test
  @DisplayName("Un espace dans un champ d'en-tête décalerait tout le reste : il est remplacé")
  void aSpaceInAHeaderFieldWouldShiftEverythingElse() {
    assertThat(Syslog.token("nom avec espace", 255)).isEqualTo("nom_avec_espace");
    assertThat(Syslog.token("coupe", 3)).isEqualTo("cou");
  }

  @Test
  @DisplayName("Le message tient sur une seule ligne")
  void theMessageFitsOnASingleLine() {
    String message =
        Syslog.format(
            20,
            Severity.CRITICAL,
            WHEN,
            "hote",
            "ALERT_OPENED",
            "Alerte",
            "Première ligne\nDeuxième ligne\r\n\tTroisième");

    assertThat(message).doesNotContain("\n").doesNotContain("\r").doesNotContain("\t");
    assertThat(message).endsWith("Alerte — Première ligne Deuxième ligne Troisième");
  }

  @Test
  @DisplayName("Un message démesuré est tronqué")
  void anOversizedMessageIsTruncated() {
    String message = Syslog.format(20, Severity.INFO, WHEN, "hote", "X", "Sujet", "a".repeat(5000));

    assertThat(message).hasSize(Syslog.MAX_LENGTH);
  }

  @Test
  @DisplayName("L'adresse du collecteur accepte udp, tcp et la forme abrégée")
  void theCollectorAddressAcceptsUdpTcpAndTheShortForm() {
    assertThat(SyslogSender.parse("udp://siem.ept.sn:514").getHost()).isEqualTo("siem.ept.sn");
    assertThat(SyslogSender.parse("tcp://siem.ept.sn:6514").getPort()).isEqualTo(6514);
    assertThat(SyslogSender.parse("siem.ept.sn:514").getScheme()).isEqualTo("udp");
    // Port absent : celui de syslog.
    assertThat(SyslogSender.parse("udp://siem.ept.sn").getPort()).isEqualTo(-1);
  }

  @Test
  @DisplayName("Une adresse de collecteur inutilisable se signale clairement")
  void anUnusableCollectorAddressSaysSoClearly() {
    assertThatThrownBy(() -> SyslogSender.parse("https://siem.ept.sn"))
        .hasMessageContaining("udp://hote:514");
    assertThatThrownBy(() -> SyslogSender.parse("udp://siem .ept.sn"))
        .hasMessageContaining("illisible");
    assertThatThrownBy(() -> SyslogSender.parse("udp:///chemin"))
        .hasMessageContaining("nom d'hôte");
  }

  private static String message(int facility, Severity severity) {
    return Syslog.format(facility, severity, WHEN, "hote", "ALERT_OPENED", "Sujet", "Corps");
  }
}

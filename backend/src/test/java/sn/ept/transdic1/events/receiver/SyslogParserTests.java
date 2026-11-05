package sn.ept.transdic1.events.receiver;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Analyse des messages syslog RFC 3164 et RFC 5424, y compris les messages mal formés. */
class SyslogParserTests {

  @Test
  void calculeLaFacilitéEtLaSévéritéDepuisLaPriorité() {
    // 165 = 20 * 8 + 5 : facilité local4, sévérité notice.
    SyslogMessage message = SyslogParser.parse("<165>Nov  5 10:00:00 routeur1 app: texte");

    assertThat(message.facility()).isEqualTo(20);
    assertThat(message.severity()).isEqualTo(5);
    assertThat(SyslogParser.parse("<0>Nov  5 10:00:00 h a: x").severity()).isZero();
    assertThat(SyslogParser.parse("<191>Nov  5 10:00:00 h a: x").facility()).isEqualTo(23);
    assertThat(SyslogParser.parse("<191>Nov  5 10:00:00 h a: x").severity()).isEqualTo(7);
  }

  @Test
  void litUnMessageRfc3164AvecNomDhôteEtÉtiquette() {
    SyslogMessage message =
        SyslogParser.parse(
            "<34>Oct 11 22:14:15 mymachine su[230]: 'su root' failed for lonvick on /dev/pts/8");

    assertThat(message.facility()).isEqualTo(4);
    assertThat(message.severity()).isEqualTo(2);
    assertThat(message.hostname()).isEqualTo("mymachine");
    assertThat(message.appName()).isEqualTo("su");
    assertThat(message.message()).isEqualTo("'su root' failed for lonvick on /dev/pts/8");
    assertThat(message.rfc5424()).isFalse();
  }

  @Test
  void litUnMessageRfc3164SansNomDhôte() {
    SyslogMessage message = SyslogParser.parse("<30>Nov  5 10:00:00 sshd: Connexion acceptée");

    assertThat(message.hostname()).isNull();
    assertThat(message.appName()).isEqualTo("sshd");
    assertThat(message.message()).isEqualTo("Connexion acceptée");
  }

  @Test
  void litUnMessageRfc5424AvecDonnéesStructurées() {
    SyslogMessage message =
        SyslogParser.parse(
            "<165>1 2003-10-11T22:14:15.003Z mymachine.example.com evntslog - ID47"
                + " [exampleSDID@32473 iut=\"3\" eventSource=\"Application\" note=\"a\\]b\"]"
                + " An application event log entry");

    assertThat(message.rfc5424()).isTrue();
    assertThat(message.facility()).isEqualTo(20);
    assertThat(message.severity()).isEqualTo(5);
    assertThat(message.hostname()).isEqualTo("mymachine.example.com");
    assertThat(message.appName()).isEqualTo("evntslog");
    assertThat(message.message()).isEqualTo("An application event log entry");
  }

  @Test
  void litUnMessageRfc5424AvecValeursNullesEtBom() {
    SyslogMessage message = SyslogParser.parse("<11>1 - - - - - - ﻿disque plein");

    assertThat(message.rfc5424()).isTrue();
    assertThat(message.severity()).isEqualTo(3);
    assertThat(message.hostname()).isNull();
    assertThat(message.appName()).isNull();
    assertThat(message.message()).isEqualTo("disque plein");
  }

  @Test
  void unMessageSansPrioritéPrendLaPrioritéParDéfaut() {
    // RFC 3164 § 4.3.3 : facilité user (1), sévérité notice (5).
    SyslogMessage message = SyslogParser.parse("un relais a transmis ce texte");

    assertThat(message.facility()).isEqualTo(1);
    assertThat(message.severity()).isEqualTo(5);
    assertThat(message.message()).isEqualTo("un relais a transmis ce texte");
  }

  @Test
  void unePrioritéHorsBornesEstTraitéeCommeAbsente() {
    SyslogMessage message = SyslogParser.parse("<999>texte");

    assertThat(message.facility()).isEqualTo(1);
    assertThat(message.severity()).isEqualTo(5);
    assertThat(message.message()).isEqualTo("<999>texte");
  }

  @Test
  void unMessageRfc5424TronquéNeLèvePasDException() {
    SyslogMessage message = SyslogParser.parse("<13>1 2024-01-01T00:00:00Z hote [sd");

    assertThat(message.rfc5424()).isTrue();
    assertThat(message.severity()).isEqualTo(5);
    assertThat(message.message()).isNotNull();
  }

  @Test
  void retireLesCaractèresDeContrôle() {
    SyslogMessage message =
        SyslogParser.parse("<13>Nov  5 10:00:00 h app: ligne1\r\nFAUX\u0007 log");

    assertThat(message.message()).doesNotContain("\r", "\n", "\u0007");
    assertThat(message.message()).contains("ligne1").contains("FAUX");
  }

  @Test
  void tronqueUnDatagrammeTropGrandÀHuitKio() {
    byte[] big =
        ("<13>Nov  5 10:00:00 h app: " + "x".repeat(20_000)).getBytes(StandardCharsets.UTF_8);

    SyslogMessage message = SyslogParser.parse(big, big.length);

    assertThat(message.truncated()).isTrue();
    assertThat(message.message().length()).isLessThanOrEqualTo(SyslogParser.MAX_BYTES);
    assertThat(SyslogParser.parse("<13>court".getBytes(StandardCharsets.UTF_8), 9).truncated())
        .isFalse();
  }

  @Test
  void unDatagrammeVideDonneUnMessageVide() {
    SyslogMessage message = SyslogParser.parse(new byte[0], 0);

    assertThat(message.message()).isEmpty();
    assertThat(message.severity()).isEqualTo(5);
  }
}

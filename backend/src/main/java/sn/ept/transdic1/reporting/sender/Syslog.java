package sn.ept.transdic1.reporting.sender;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import sn.ept.transdic1.alerting.domain.Severity;

/**
 * Mise en forme d'un message syslog RFC 5424, pour l'envoi des alertes vers un SIEM (S8).
 *
 * <p>Calcul pur, sans réseau ni base : la forme exacte du message est ce qui décide si le
 * collecteur saura le lire, elle se teste donc caractère par caractère.
 *
 * <pre>
 * &lt;PRI&gt;1 HORODATAGE HÔTE APPLICATION PROCESSUS IDENTIFIANT [DONNÉES] MESSAGE
 * </pre>
 */
public final class Syslog {
  /** Nom d'application annoncé au collecteur. */
  public static final String APP_NAME = "trans-dic1";

  /** Longueur maximale d'un datagramme : au-delà, le message est tronqué. */
  public static final int MAX_LENGTH = 2048;

  private static final DateTimeFormatter TIMESTAMP =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT)
          .withZone(ZoneOffset.UTC);

  private Syslog() {}

  /**
   * Gravité syslog (0 à 7) correspondant à la sévérité d'une alerte.
   *
   * <p>Une alerte critique vaut {@code critical} (2) et non {@code emergency} : l'urgence syslog
   * annonce un système inutilisable, ce qu'une alerte de supervision ne dit pas.
   */
  public static int severity(Severity severity) {
    if (severity == null) {
      return 5; // notice : on ne sait pas, on ne dramatise pas
    }
    return switch (severity) {
      case CRITICAL -> 2;
      case WARNING -> 4;
      case INFO -> 6;
    };
  }

  /**
   * Message complet, prêt à envoyer.
   *
   * @param facility code d'origine syslog (0 à 23), à accorder avec ce qu'attend le collecteur
   * @param hostname nom annoncé comme origine ; {@code -} si inconnu
   * @param messageId court identifiant du type de message (type d'alerte, par exemple)
   */
  public static String format(
      int facility,
      Severity severity,
      Instant when,
      String hostname,
      String messageId,
      String subject,
      String body) {
    int priority = Math.floorMod(facility, 24) * 8 + severity(severity);
    String text =
        (subject == null ? "" : subject)
            + (body == null || body.isBlank()
                ? ""
                : " — " + body.replace('\n', ' ').replace('\r', ' '));
    String message =
        "<"
            + priority
            + ">1 "
            + TIMESTAMP.format(when)
            + " "
            + token(hostname, 255)
            + " "
            + APP_NAME
            + " - "
            + token(messageId, 32)
            + " - "
            + collapse(text);
    return message.length() > MAX_LENGTH ? message.substring(0, MAX_LENGTH) : message;
  }

  /**
   * Champ d'en-tête : pas d'espace ni de caractère de contrôle, sinon le collecteur décale tout le
   * reste du message. Vide devient {@code -}, la valeur nulle de la norme.
   */
  static String token(String value, int max) {
    if (value == null || value.isBlank()) {
      return "-";
    }
    var cleaned = new StringBuilder(value.length());
    for (char c : value.toCharArray()) {
      cleaned.append(c <= ' ' || c == 127 ? '_' : c);
    }
    String result = cleaned.toString();
    return result.length() > max ? result.substring(0, max) : result;
  }

  /** Message sur une seule ligne : un retour à la ligne couperait le datagramme en deux. */
  static String collapse(String value) {
    var cleaned = new StringBuilder(value.length());
    for (char c : value.toCharArray()) {
      cleaned.append(c == '\n' || c == '\r' || c == '\t' ? ' ' : c);
    }
    return cleaned.toString().replaceAll(" {2,}", " ").trim();
  }
}

package sn.ept.transdic1.events.receiver;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import sn.ept.transdic1.events.domain.EventText;

/**
 * Analyse tolérante d'un message syslog (RFC 5424, sinon RFC 3164). Elle ne lève jamais d'exception
 * : un champ illisible reste nul et le texte brut devient le message. Les caractères de contrôle
 * sont retirés et la taille est bornée à {@value #MAX_BYTES} octets.
 */
public final class SyslogParser {
  /** Taille maximale d'un datagramme retenue ; au-delà il est tronqué. */
  public static final int MAX_BYTES = 8192;

  /** Priorité par défaut d'un message qui n'en porte pas : user.notice (RFC 3164 § 4.3.3). */
  private static final int DEFAULT_FACILITY = 1;

  private static final int DEFAULT_SEVERITY = 5;

  private static final Pattern RFC3164_TIMESTAMP =
      Pattern.compile("^[A-Za-z]{3} [ \\d]?\\d \\d{2}:\\d{2}:\\d{2}(\\.\\d{1,6})?\\s+");
  private static final Pattern TAG =
      Pattern.compile(
          "^[A-Za-z0-9_.,/\\-]{1,48}(\\[[^\\]]{0,32}\\])?:$|^[A-Za-z0-9_.,/\\-]{1,48}\\[[^\\]]{0,32}\\]$");

  private SyslogParser() {}

  /** Décode un datagramme (UTF-8, octets invalides remplacés), tronqué à {@value #MAX_BYTES}. */
  public static SyslogMessage parse(byte[] data, int length) {
    boolean truncated = length > MAX_BYTES;
    int kept = Math.min(Math.max(length, 0), MAX_BYTES);
    String text = new String(data, 0, kept, StandardCharsets.UTF_8);
    SyslogMessage message = parse(text);
    return truncated
        ? new SyslogMessage(
            message.facility(),
            message.severity(),
            message.hostname(),
            message.appName(),
            message.message(),
            message.rfc5424(),
            true)
        : message;
  }

  public static SyslogMessage parse(String raw) {
    String text = raw == null ? "" : raw;
    int facility = DEFAULT_FACILITY;
    int severity = DEFAULT_SEVERITY;
    String rest = text;
    int priority = priority(text);
    if (priority >= 0) {
      facility = priority / 8;
      severity = priority % 8;
      rest = text.substring(text.indexOf('>') + 1);
    }
    if (rest.startsWith("1 ")) {
      return rfc5424(facility, severity, rest.substring(2));
    }
    return rfc3164(facility, severity, rest);
  }

  /** Valeur de {@code <PRI>} (0 à 191), ou -1 si absente ou invalide. */
  static int priority(String text) {
    if (!text.startsWith("<")) {
      return -1;
    }
    int end = text.indexOf('>');
    if (end < 2 || end > 4) {
      return -1;
    }
    int value = 0;
    for (int i = 1; i < end; i++) {
      char c = text.charAt(i);
      if (c < '0' || c > '9') {
        return -1;
      }
      value = value * 10 + (c - '0');
    }
    return value <= 191 ? value : -1;
  }

  private static SyslogMessage rfc5424(int facility, int severity, String rest) {
    String[] header = new String[5]; // horodatage, hôte, application, processus, identifiant
    int pos = 0;
    for (int i = 0; i < header.length; i++) {
      int space = rest.indexOf(' ', pos);
      if (space < 0) {
        // En-tête incomplet : ce qui reste devient le message.
        return message5424(facility, severity, header, rest.substring(pos));
      }
      header[i] = rest.substring(pos, space);
      pos = space + 1;
    }
    if (rest.startsWith("-", pos)) {
      pos++;
    } else {
      while (pos < rest.length() && rest.charAt(pos) == '[') {
        int end = endOfElement(rest, pos);
        if (end < 0) {
          break; // donnée structurée mal fermée : on la garde dans le message
        }
        pos = end + 1;
      }
    }
    if (pos < rest.length() && rest.charAt(pos) == ' ') {
      pos++;
    }
    return message5424(facility, severity, header, rest.substring(Math.min(pos, rest.length())));
  }

  /** Position du {@code ]} qui ferme l'élément ouvert en {@code start}, ou -1. */
  private static int endOfElement(String text, int start) {
    boolean quoted = false;
    for (int i = start + 1; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '\\') {
        i++;
      } else if (c == '"') {
        quoted = !quoted;
      } else if (c == ']' && !quoted) {
        return i;
      }
    }
    return -1;
  }

  private static SyslogMessage message5424(
      int facility, int severity, String[] header, String message) {
    return new SyslogMessage(
        facility,
        severity,
        nil(header[1], EventText.MAX_TITLE),
        nil(header[2], 64),
        cleanMessage(message),
        true,
        false);
  }

  private static String nil(String value, int max) {
    return value == null || value.equals("-") ? null : EventText.cleanOrNull(value, max);
  }

  private static SyslogMessage rfc3164(int facility, int severity, String rest) {
    var timestamp = RFC3164_TIMESTAMP.matcher(rest);
    String hostname = null;
    if (timestamp.find()) {
      rest = rest.substring(timestamp.end());
      String first = firstToken(rest);
      if (!first.isEmpty() && !TAG.matcher(first).matches()) {
        hostname = first;
        rest = rest.substring(first.length()).stripLeading();
      }
    }
    String appName = null;
    String token = firstToken(rest);
    if (!token.isEmpty() && TAG.matcher(token).matches()) {
      String tag = token.endsWith(":") ? token.substring(0, token.length() - 1) : token;
      int bracket = tag.indexOf('[');
      appName = bracket < 0 ? tag : tag.substring(0, bracket);
      rest = rest.substring(token.length()).stripLeading();
    }
    return new SyslogMessage(
        facility,
        severity,
        EventText.cleanOrNull(hostname, EventText.MAX_TITLE),
        EventText.cleanOrNull(appName, 64),
        cleanMessage(rest),
        false,
        false);
  }

  private static String firstToken(String text) {
    int space = text.indexOf(' ');
    return space < 0 ? text : text.substring(0, space);
  }

  private static String cleanMessage(String message) {
    String clean = EventText.clean(message, MAX_BYTES);
    return clean == null ? "" : clean;
  }
}

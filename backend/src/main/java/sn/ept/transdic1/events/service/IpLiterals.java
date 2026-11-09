package sn.ept.transdic1.events.service;

import java.util.Locale;

/**
 * Forme canonique des adresses IP littérales (ADR 0021), pour comparer l'adresse source d'un
 * datagramme à l'adresse de gestion saisie dans l'inventaire : IPv4 en décimal pointé sans zéros en
 * tête, IPv6 compressée selon la RFC 5952 (minuscules, plus longue suite de zéros remplacée par
 * {@code ::}), IPv4 mappée en IPv6 déballée, indice de zone retiré. L'analyse est purement
 * textuelle : aucune résolution DNS. Ce qui n'est pas une adresse littérale (nom d'hôte, texte
 * invalide) est rendu en minuscules, sans espaces autour.
 */
public final class IpLiterals {
  private IpLiterals() {}

  public static String canonical(String address) {
    if (address == null) {
      return null;
    }
    String text = address.trim().toLowerCase(Locale.ROOT);
    if (text.startsWith("[") && text.endsWith("]")) {
      text = text.substring(1, text.length() - 1);
    }
    int[] v4 = parseIpv4(text);
    if (v4 != null) {
      return ipv4(v4);
    }
    if (text.indexOf(':') >= 0) {
      String withoutZone = text;
      int zone = text.indexOf('%');
      if (zone >= 0) {
        withoutZone = text.substring(0, zone);
      }
      int[] groups = parseIpv6(withoutZone);
      if (groups != null) {
        return ipv6(groups);
      }
    }
    return text;
  }

  private static int[] parseIpv4(String text) {
    String[] parts = text.split("\\.", -1);
    if (parts.length != 4) {
      return null;
    }
    int[] out = new int[4];
    for (int i = 0; i < 4; i++) {
      String p = parts[i];
      if (p.isEmpty() || p.length() > 3) {
        return null;
      }
      for (int c = 0; c < p.length(); c++) {
        if (!Character.isDigit(p.charAt(c)) || p.charAt(c) > '9') {
          return null;
        }
      }
      int value = Integer.parseInt(p);
      if (value > 255) {
        return null;
      }
      out[i] = value;
    }
    return out;
  }

  /** Huit groupes de 16 bits, ou null si le texte n'est pas une adresse IPv6 littérale. */
  private static int[] parseIpv6(String text) {
    if (text.isEmpty()) {
      return null;
    }
    int doubleColon = text.indexOf("::");
    if (doubleColon >= 0 && text.indexOf("::", doubleColon + 1) >= 0) {
      return null;
    }
    String head = doubleColon >= 0 ? text.substring(0, doubleColon) : text;
    String tail = doubleColon >= 0 ? text.substring(doubleColon + 2) : "";
    int[] headGroups = groups(head);
    int[] tailGroups = groups(tail);
    if (headGroups == null || tailGroups == null) {
      return null;
    }
    int total = headGroups.length + tailGroups.length;
    if (doubleColon >= 0 ? total > 7 : total != 8) {
      return null;
    }
    int[] out = new int[8];
    System.arraycopy(headGroups, 0, out, 0, headGroups.length);
    System.arraycopy(tailGroups, 0, out, 8 - tailGroups.length, tailGroups.length);
    return out;
  }

  /** Groupes hexadécimaux séparés par « : » ; un IPv4 final compte pour deux groupes. */
  private static int[] groups(String part) {
    if (part.isEmpty()) {
      return new int[0];
    }
    String[] tokens = part.split(":", -1);
    int[] out = new int[tokens.length + 1];
    int n = 0;
    for (int i = 0; i < tokens.length; i++) {
      String token = tokens[i];
      if (i == tokens.length - 1 && token.indexOf('.') >= 0) {
        int[] v4 = parseIpv4(token);
        if (v4 == null) {
          return null;
        }
        out[n++] = v4[0] << 8 | v4[1];
        out[n++] = v4[2] << 8 | v4[3];
        continue;
      }
      if (token.isEmpty() || token.length() > 4) {
        return null;
      }
      int value = 0;
      for (int c = 0; c < token.length(); c++) {
        int digit = Character.digit(token.charAt(c), 16);
        if (digit < 0) {
          return null;
        }
        value = value << 4 | digit;
      }
      out[n++] = value;
    }
    int[] trimmed = new int[n];
    System.arraycopy(out, 0, trimmed, 0, n);
    return trimmed;
  }

  private static String ipv4(int[] v) {
    return v[0] + "." + v[1] + "." + v[2] + "." + v[3];
  }

  private static String ipv6(int[] g) {
    boolean mapped = g[5] == 0xffff;
    for (int i = 0; i < 5 && mapped; i++) {
      mapped = g[i] == 0;
    }
    if (mapped) {
      return ipv4(new int[] {g[6] >> 8, g[6] & 255, g[7] >> 8, g[7] & 255});
    }
    // Plus longue suite d'au moins deux groupes nuls (la première en cas d'égalité).
    int bestStart = -1;
    int bestLength = 1;
    for (int i = 0; i < 8; ) {
      if (g[i] != 0) {
        i++;
        continue;
      }
      int j = i;
      while (j < 8 && g[j] == 0) {
        j++;
      }
      if (j - i > bestLength) {
        bestStart = i;
        bestLength = j - i;
      }
      i = j;
    }
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < 8; i++) {
      if (i == bestStart) {
        out.append("::");
        i += bestLength - 1;
        continue;
      }
      if (out.length() > 0 && out.charAt(out.length() - 1) != ':') {
        out.append(':');
      }
      out.append(Integer.toHexString(g[i]));
    }
    return out.toString();
  }
}

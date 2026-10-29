package sn.ept.transdic1.reporting.sender;

/** Échappement JSON minimal pour les charges utiles des canaux (pas de dépendance de plus). */
final class Json {
  private Json() {}

  static String quote(String value) {
    StringBuilder out = new StringBuilder("\"");
    for (char c : (value == null ? "" : value).toCharArray()) {
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    return out.append('"').toString();
  }
}

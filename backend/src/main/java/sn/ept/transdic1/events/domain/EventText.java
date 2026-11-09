package sn.ept.transdic1.events.domain;

/**
 * Nettoyage des textes reçus du réseau : caractères de contrôle remplacés par une espace (pas
 * d'injection dans les journaux ni dans l'interface), longueur bornée.
 */
public final class EventText {
  public static final int MAX_TITLE = 255;
  public static final int MAX_DETAILS = 4096;

  private EventText() {}

  /** Retire les caractères de contrôle, réduit les espaces multiples et tronque à {@code max}. */
  public static String clean(String text, int max) {
    if (text == null) {
      return null;
    }
    StringBuilder out = new StringBuilder(Math.min(text.length(), max));
    boolean space = false;
    for (int i = 0; i < text.length() && out.length() < max; i++) {
      char c = text.charAt(i);
      if (Character.isISOControl(c) || c == ' ' || c == ' ' || c == '﻿') {
        c = ' ';
      }
      if (c == ' ') {
        if (space) {
          continue;
        }
        space = true;
      } else {
        space = false;
      }
      out.append(c);
    }
    return out.toString().strip();
  }

  /** Comme {@link #clean} ; une chaîne vide devient nulle. */
  public static String cleanOrNull(String text, int max) {
    String clean = clean(text, max);
    return clean == null || clean.isEmpty() ? null : clean;
  }
}

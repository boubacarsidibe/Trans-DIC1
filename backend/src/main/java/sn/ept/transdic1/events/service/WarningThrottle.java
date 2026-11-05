package sn.ept.transdic1.events.service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import sn.ept.transdic1.events.domain.EventText;

/**
 * Limite un avertissement répétitif à une fois par clé et par période (par exemple une règle dont
 * l'expression est interrompue à chaque syslog usurpé) : pas d'inondation des journaux. La table
 * est bornée.
 */
public class WarningThrottle {
  private static final int MAX_KEYS = 1_000;
  private static final int MAX_LOGGED_LENGTH = 120;

  private final long periodMillis;
  private final ConcurrentMap<Object, Long> lastLogged = new ConcurrentHashMap<>();

  public WarningThrottle(long periodMillis) {
    this.periodMillis = periodMillis;
  }

  /** Vrai si l'avertissement de cette clé peut être émis à l'instant {@code nowMillis}. */
  public boolean allow(Object key, long nowMillis) {
    if (lastLogged.size() >= MAX_KEYS && !lastLogged.containsKey(key)) {
      lastLogged.clear();
    }
    boolean[] allowed = {false};
    lastLogged.compute(
        key,
        (k, last) -> {
          if (last == null || nowMillis - last >= periodMillis) {
            allowed[0] = true;
            return nowMillis;
          }
          return last;
        });
    return allowed[0];
  }

  /** Texte sûr pour une ligne de journal : sans caractères de contrôle, longueur bornée. */
  public static String loggable(String text) {
    return EventText.clean(text, MAX_LOGGED_LENGTH);
  }
}

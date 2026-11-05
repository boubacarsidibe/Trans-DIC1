package sn.ept.transdic1.events.domain;

/**
 * Sévérité d'un événement, sur l'échelle syslog (RFC 5424) : l'ordre des constantes est le code
 * syslog, de 0 (la plus grave) à 7. Les traps SNMP y sont rangés par une table fixe.
 */
public enum EventSeverity {
  EMERGENCY,
  ALERT,
  CRITICAL,
  ERROR,
  WARNING,
  NOTICE,
  INFO,
  DEBUG;

  /** Code syslog 0 à 7 ; hors bornes, NOTICE (valeur par défaut de la RFC 3164). */
  public static EventSeverity fromCode(int code) {
    return code >= 0 && code < values().length ? values()[code] : NOTICE;
  }

  /** Vrai si cette sévérité est au moins aussi grave que {@code minimum}. */
  public boolean atLeast(EventSeverity minimum) {
    return ordinal() <= minimum.ordinal();
  }
}

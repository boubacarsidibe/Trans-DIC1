package sn.ept.transdic1.reporting.domain;

public enum NotificationChannel {
  EMAIL,
  WEBHOOK,
  TEAMS,
  SLACK,
  TELEGRAM,
  /**
   * Collecteur syslog d'un SIEM (ADR 0031) : l'alerte part en RFC 5424 vers Wazuh ou équivalent.
   */
  SYSLOG
}

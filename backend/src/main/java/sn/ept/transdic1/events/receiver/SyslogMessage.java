package sn.ept.transdic1.events.receiver;

/**
 * Message syslog analysé. {@code facility} 0 à 23 et {@code severity} 0 à 7 viennent de la priorité
 * ({@code PRI = facility * 8 + severity}). {@code hostname} et {@code appName} sont nuls s'ils
 * manquent ; {@code truncated} indique un datagramme coupé à 8 Kio.
 */
public record SyslogMessage(
    int facility,
    int severity,
    String hostname,
    String appName,
    String message,
    boolean rfc5424,
    boolean truncated) {}

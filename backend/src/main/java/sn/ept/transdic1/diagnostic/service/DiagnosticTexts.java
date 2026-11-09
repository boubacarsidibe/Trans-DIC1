package sn.ept.transdic1.diagnostic.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

/**
 * Textes du diagnostic : nettoyage des chaînes venues de la base ou du réseau, libellés français et
 * heures lisibles. Aucune phrase n'est générée librement : ce sont des gabarits fixes complétés par
 * des faits.
 */
final class DiagnosticTexts {
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.FRANCE);
  private static final DateTimeFormatter DATE_TIME =
      DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.FRANCE);

  /** Libellés des actions d'audit retenues comme « changements » (liste blanche). */
  static final Map<String, String> CHANGE_LABELS =
      Map.ofEntries(
          Map.entry("RULE_CREATED", "Règle d'alerte créée"),
          Map.entry("RULE_UPDATED", "Règle d'alerte modifiée"),
          Map.entry("EVENT_RULE_CREATED", "Règle d'événement créée"),
          Map.entry("EVENT_RULE_UPDATED", "Règle d'événement modifiée"),
          Map.entry("EQUIPMENT_UPDATED", "Fiche d'équipement modifiée"),
          Map.entry("TEMPLATE_APPLIED", "Modèle d'équipement appliqué"),
          Map.entry("COLLECTION_CONFIGURED", "Collecte SNMP configurée"),
          Map.entry("COLLECTION_DISABLED", "Collecte SNMP désactivée"),
          Map.entry("AGENT_REGISTERED", "Agent enregistré (nouveau jeton)"),
          Map.entry("AGENT_REVOKED", "Agent révoqué"),
          Map.entry("TOPOLOGY_LINK_ADDED", "Lien de topologie ajouté"),
          Map.entry("TOPOLOGY_LINK_REMOVED", "Lien de topologie retiré"),
          Map.entry("PROBE_CREATED", "Sonde créée"),
          Map.entry("PROBE_UPDATED", "Sonde modifiée"),
          Map.entry("PROBE_DISABLED", "Sonde désactivée"),
          Map.entry("MAINTENANCE_PLANNED", "Maintenance planifiée"),
          Map.entry("MAINTENANCE_CANCELLED", "Maintenance annulée"),
          Map.entry("SITE_UPDATED", "Site modifié"));

  private static final Map<String, String> TARGET_LABELS =
      Map.of(
          "EQUIPMENT", "Équipement",
          "ALERT_RULE", "Règle",
          "EVENT_RULE", "Règle d'événement",
          "PROBE", "Sonde",
          "MAINTENANCE", "Maintenance de",
          "SITE", "Site");

  private static final Map<String, String> ALERT_TYPES =
      Map.of(
          "THRESHOLD", "de seuil",
          "COLLECTION", "de collecte",
          "ANOMALY", "d'anomalie",
          "FORECAST", "de prévision",
          "EVENT", "d'événement");

  private static final Map<String, String> EQUIPMENT_ARTICLES =
      Map.of(
          "ROUTER", "Le routeur",
          "SWITCH", "Le commutateur",
          "SERVER", "Le serveur",
          "VIRTUAL_MACHINE", "La machine virtuelle",
          "WIFI_ACCESS_POINT", "Le point d'accès Wi-Fi");

  private final ZoneId zone;
  private final Instant now;

  DiagnosticTexts(ZoneId zone, Instant now) {
    this.zone = zone;
    this.now = now;
  }

  /**
   * Retire les caractères de contrôle (injection dans l'affichage ou les journaux), resserre les
   * espaces et borne la longueur. Le frontend échappe en plus tout le texte (React).
   */
  static String clean(String text, int max) {
    if (text == null) {
      return null;
    }
    String cleaned = text.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s{2,}", " ").trim();
    return cleaned.length() > max ? cleaned.substring(0, max - 1) + "…" : cleaned;
  }

  /** « 14:02 » le jour même, « 04/11/2026 14:02 » sinon (fuseau {@code diagnostic.time-zone}). */
  String time(Instant at) {
    if (at == null) {
      return "?";
    }
    var local = at.atZone(zone);
    return local.toLocalDate().equals(LocalDate.ofInstant(now, zone))
        ? TIME.format(local)
        : DATE_TIME.format(local);
  }

  static String alertType(String type) {
    return ALERT_TYPES.getOrDefault(type, "");
  }

  /** « Le routeur R1 », « L'équipement X » pour un type inconnu. */
  static String subject(String equipmentType, String name) {
    return EQUIPMENT_ARTICLES.getOrDefault(equipmentType, "L'équipement") + " " + name;
  }

  static String changeLabel(String action) {
    return CHANGE_LABELS.getOrDefault(action, action);
  }

  /** « Règle « CPU haut » », « Équipement « R1 » » ; jamais de détail de l'entrée d'audit. */
  static String target(String targetType, String targetName) {
    String label = TARGET_LABELS.getOrDefault(targetType, "Cible");
    return targetName == null
        ? label + " (supprimée depuis)"
        : label + " « " + clean(targetName, 120) + " »";
  }

  static String sourceLabel(String source) {
    return "SNMP_TRAP".equals(source) ? "Trap SNMP" : "Message syslog";
  }

  /** Minutes entières entre deux instants, au moins 0. */
  static long minutesBetween(Instant earlier, Instant later) {
    return Math.max(0, java.time.Duration.between(earlier, later).toMinutes());
  }

  static String plural(long count, String singular, String plural) {
    return count + " " + (count > 1 ? plural : singular);
  }
}

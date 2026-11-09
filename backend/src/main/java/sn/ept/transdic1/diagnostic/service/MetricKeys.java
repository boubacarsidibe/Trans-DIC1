package sn.ept.transdic1.diagnostic.service;

import java.util.UUID;

/**
 * Indicateur visé par une alerte : celui de sa règle, ou celui inscrit dans sa clé d'occurrence.
 */
final class MetricKeys {
  private MetricKeys() {}

  /**
   * THRESHOLD : indicateur de la règle ; ANOMALY : {@code anomaly:<équipement>:<clé>:<instant>} ;
   * FORECAST : {@code forecast:<équipement>:<clé>@<instant>} ; sinon null.
   */
  static String of(String type, String occurrenceKey, UUID equipmentId, String ruleMetric) {
    if ("THRESHOLD".equals(type)) {
      return ruleMetric;
    }
    if (occurrenceKey == null) {
      return null;
    }
    if ("ANOMALY".equals(type)) {
      String prefix = "anomaly:" + equipmentId + ":";
      int end = occurrenceKey.lastIndexOf(':');
      return occurrenceKey.startsWith(prefix) && end > prefix.length()
          ? occurrenceKey.substring(prefix.length(), end)
          : null;
    }
    if ("FORECAST".equals(type)) {
      String prefix = "forecast:" + equipmentId + ":";
      int end = occurrenceKey.indexOf('@');
      return occurrenceKey.startsWith(prefix) && end > prefix.length()
          ? occurrenceKey.substring(prefix.length(), end)
          : null;
    }
    return null;
  }

  /** Préfixe de clé qui regroupe les alertes successives d'un même indicateur (ou null). */
  static String occurrencePrefix(String type, String occurrenceKey) {
    if (occurrenceKey == null) {
      return null;
    }
    if ("ANOMALY".equals(type)) {
      int end = occurrenceKey.lastIndexOf(':');
      return end > 0 ? occurrenceKey.substring(0, end + 1) : null;
    }
    if ("FORECAST".equals(type)) {
      int end = occurrenceKey.indexOf('@');
      return end > 0 ? occurrenceKey.substring(0, end + 1) : null;
    }
    return null;
  }
}

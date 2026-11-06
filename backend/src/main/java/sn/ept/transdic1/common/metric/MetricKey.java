package sn.ept.transdic1.common.metric;

import java.util.List;

/**
 * Clé d'une mesure, avec instance facultative : {@code disk.usage[C:]}. Les clés sans crochets
 * restent valides. {@code famille[*]} désigne, dans une règle, toutes les instances de la famille.
 */
public final class MetricKey {
  public static final String ANY = "[*]";

  private MetricKey() {}

  /** Partie avant les crochets : {@code disk.usage}. */
  public static String family(String key) {
    int open = key.indexOf('[');
    return open < 0 ? key : key.substring(0, open);
  }

  /** Contenu des crochets : {@code C:}, ou null pour une clé sans instance. */
  public static String instance(String key) {
    int open = key.indexOf('[');
    if (open < 0 || !key.endsWith("]")) {
      return null;
    }
    return key.substring(open + 1, key.length() - 1);
  }

  public static boolean isWildcard(String key) {
    return key.endsWith(ANY);
  }

  /**
   * Types de règle qui visent cette mesure : la clé exacte et, si elle a une instance, famille[*].
   */
  public static List<String> ruleTypes(String key) {
    return instance(key) == null ? List.of(key) : List.of(key, family(key) + ANY);
  }
}

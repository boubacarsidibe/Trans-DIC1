package sn.ept.transdic1.collection.probe;

/**
 * Résultat d'une sonde ; {@code error} est un court message français fixe, jamais brut.
 *
 * <p>{@code indeterminate} : la sonde n'a pas pu conclure (par exemple ping refusé au processus du
 * serveur). Ni en ligne ni en panne : aucune mesure {@code probe.up} n'est stockée et le compte des
 * échecs consécutifs ne bouge pas.
 *
 * <p>{@code lossPercent} et {@code jitterMs} ne sont renseignés que par la sonde ICMP quand elle
 * envoie plusieurs paquets.
 */
public record ProbeResult(
    boolean up,
    Integer latencyMs,
    Integer tlsDaysLeft,
    String error,
    boolean indeterminate,
    Integer lossPercent,
    Double jitterMs) {
  public ProbeResult(
      boolean up, Integer latencyMs, Integer tlsDaysLeft, String error, boolean indeterminate) {
    this(up, latencyMs, tlsDaysLeft, error, indeterminate, null, null);
  }

  public ProbeResult(boolean up, Integer latencyMs, Integer tlsDaysLeft, String error) {
    this(up, latencyMs, tlsDaysLeft, error, false);
  }

  /** Ping à plusieurs paquets : latence moyenne, part de paquets perdus et gigue (si mesurable). */
  public static ProbeResult upWithQuality(int latencyMs, int lossPercent, Double jitterMs) {
    return new ProbeResult(true, latencyMs, null, null, false, lossPercent, jitterMs);
  }

  /** Aucun paquet n'est revenu d'un ping à plusieurs paquets : perte de 100 %. */
  public static ProbeResult downAllLost(String error) {
    return new ProbeResult(false, null, null, error, false, 100, null);
  }

  public static ProbeResult up(int latencyMs) {
    return new ProbeResult(true, latencyMs, null, null);
  }

  public static ProbeResult up(int latencyMs, Integer tlsDaysLeft) {
    return new ProbeResult(true, latencyMs, tlsDaysLeft, null);
  }

  public static ProbeResult down(String error) {
    return new ProbeResult(false, null, null, error);
  }

  public static ProbeResult indeterminate(String error) {
    return new ProbeResult(false, null, null, error, true);
  }
}

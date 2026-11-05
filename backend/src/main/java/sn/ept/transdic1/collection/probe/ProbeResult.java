package sn.ept.transdic1.collection.probe;

/**
 * Résultat d'une sonde ; {@code error} est un court message français fixe, jamais brut.
 *
 * <p>{@code indeterminate} : la sonde n'a pas pu conclure (par exemple ping refusé au processus du
 * serveur). Ni en ligne ni en panne : aucune mesure {@code probe.up} n'est stockée et le compte des
 * échecs consécutifs ne bouge pas.
 */
public record ProbeResult(
    boolean up, Integer latencyMs, Integer tlsDaysLeft, String error, boolean indeterminate) {
  public ProbeResult(boolean up, Integer latencyMs, Integer tlsDaysLeft, String error) {
    this(up, latencyMs, tlsDaysLeft, error, false);
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

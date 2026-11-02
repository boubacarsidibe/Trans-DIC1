package sn.ept.transdic1.collection.probe;

/** Résultat d'une sonde ; {@code error} est un court message français fixe, jamais brut. */
public record ProbeResult(boolean up, Integer latencyMs, Integer tlsDaysLeft, String error) {
  public static ProbeResult up(int latencyMs) {
    return new ProbeResult(true, latencyMs, null, null);
  }

  public static ProbeResult up(int latencyMs, Integer tlsDaysLeft) {
    return new ProbeResult(true, latencyMs, tlsDaysLeft, null);
  }

  public static ProbeResult down(String error) {
    return new ProbeResult(false, null, null, error);
  }
}

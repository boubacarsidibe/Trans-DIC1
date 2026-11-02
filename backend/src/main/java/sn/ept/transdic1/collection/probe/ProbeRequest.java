package sn.ept.transdic1.collection.probe;

import java.net.InetAddress;
import java.time.Duration;
import java.util.Objects;

/**
 * Cible d'une sonde. L'adresse est déjà validée par {@link ProbeTargetGuard} : les sondes se
 * connectent uniquement à elle et ne résolvent jamais le nom d'hôte.
 */
public record ProbeRequest(
    InetAddress address,
    String host,
    Integer port,
    String path,
    Duration timeout,
    String expectedStatus,
    String expectedText) {
  /** Délai maximal d'une sonde. */
  public static final Duration MAX_TIMEOUT = Duration.ofSeconds(30);

  public ProbeRequest {
    // Sans adresse, un socket se connecterait à l'adresse joker (services locaux).
    Objects.requireNonNull(address, "Adresse manquante");
    if (timeout == null) {
      throw new IllegalArgumentException("Délai manquant");
    }
    if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(MAX_TIMEOUT) > 0) {
      throw new IllegalArgumentException("Délai hors limites");
    }
  }
}

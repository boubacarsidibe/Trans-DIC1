package sn.ept.transdic1.collection.probe;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Garde anti-SSRF des sondes sans agent : refuse les cibles qui pointent vers la machine du serveur
 * ou ses services de métadonnées. Le LAN privé reste autorisé, c'est la cible normale.
 */
@Component
public class ProbeTargetGuard {
  private final Function<String, InetAddress[]> resolver;

  @Autowired
  public ProbeTargetGuard() {
    this(
        host -> {
          try {
            return InetAddress.getAllByName(host);
          } catch (UnknownHostException e) {
            throw new ProbeTargetException("Hôte introuvable : " + host);
          }
        });
  }

  /** Constructeur de test : permet d'injecter un faux DNS. */
  ProbeTargetGuard(Function<String, InetAddress[]> resolver) {
    this.resolver = resolver;
  }

  /** Retourne la première adresse autorisée de l'hôte, ou refuse la cible. */
  public InetAddress resolve(String host) {
    if (host == null || host.isBlank()) {
      throw new ProbeTargetException("Hôte vide");
    }
    String trimmed = host.trim();
    // Un hôte commençant par « - » serait pris pour une option par ping ou nmap.
    if (trimmed.startsWith("-")) {
      throw new ProbeTargetException("Hôte invalide : " + trimmed);
    }
    InetAddress[] addresses = resolver.apply(trimmed);
    for (InetAddress address : addresses) {
      if (isAllowed(unmap(address))) {
        return address;
      }
    }
    throw new ProbeTargetException("Cible interdite : " + trimmed);
  }

  /**
   * Variante stricte pour un client HTTP qui résout lui-même le nom (connecteur d'hyperviseur, ADR
   * 0055) : <b>toutes</b> les adresses du nom doivent être autorisées, sinon le client pourrait
   * choisir celle qui est interdite. Retourne les adresses, ou refuse la cible.
   */
  public InetAddress[] resolveAll(String host) {
    if (host == null || host.isBlank() || host.trim().startsWith("-")) {
      throw new ProbeTargetException("Hôte invalide : " + host);
    }
    String trimmed = host.trim();
    InetAddress[] addresses = resolver.apply(trimmed);
    if (addresses.length == 0) {
      throw new ProbeTargetException("Hôte introuvable : " + trimmed);
    }
    for (InetAddress address : addresses) {
      if (!isAllowed(unmap(address))) {
        throw new ProbeTargetException("Cible interdite : " + trimmed);
      }
    }
    return addresses;
  }

  /** Ramène une adresse IPv6 mappée (::ffff:a.b.c.d) à son IPv4 pour contrôler la vraie cible. */
  private static InetAddress unmap(InetAddress address) {
    if (address instanceof Inet6Address v6) {
      byte[] b = v6.getAddress();
      boolean mapped = true;
      for (int i = 0; i < 10; i++) {
        mapped &= b[i] == 0;
      }
      if (mapped && b[10] == (byte) 0xff && b[11] == (byte) 0xff) {
        try {
          return InetAddress.getByAddress(new byte[] {b[12], b[13], b[14], b[15]});
        } catch (UnknownHostException e) {
          return address;
        }
      }
    }
    return address;
  }

  private static boolean isAllowed(InetAddress a) {
    return !(a.isLoopbackAddress()
        || a.isLinkLocalAddress()
        || a.isAnyLocalAddress()
        || a.isMulticastAddress());
  }
}

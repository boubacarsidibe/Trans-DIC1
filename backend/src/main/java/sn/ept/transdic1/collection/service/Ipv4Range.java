package sn.ept.transdic1.collection.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plage IPv4 au format CIDR (192.0.2.0/28). Analysée sans résolution DNS ; seules les adresses
 * d'hôtes sont parcourues (ni l'adresse du réseau ni la diffusion, sauf pour /31 et /32).
 */
public record Ipv4Range(long network, int prefix) {
  private static final Pattern CIDR =
      Pattern.compile("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})/(\\d{1,2})");

  public static Ipv4Range parse(String cidr) {
    Matcher m = CIDR.matcher(cidr == null ? "" : cidr.trim());
    if (!m.matches()) {
      throw new IllegalArgumentException(
          "Plage invalide (format attendu : 192.0.2.0/28) : " + cidr);
    }
    long address = 0;
    for (int i = 1; i <= 4; i++) {
      int octet = Integer.parseInt(m.group(i));
      if (octet > 255) {
        throw new IllegalArgumentException("Adresse invalide : " + cidr);
      }
      address = (address << 8) | octet;
    }
    int prefix = Integer.parseInt(m.group(5));
    if (prefix > 32) {
      throw new IllegalArgumentException("Préfixe invalide : " + cidr);
    }
    long mask = mask(prefix);
    if ((address & mask) != address) {
      throw new IllegalArgumentException("Adresse de réseau attendue pour " + cidr);
    }
    return new Ipv4Range(address, prefix);
  }

  private static long mask(int prefix) {
    return prefix == 0 ? 0 : (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
  }

  /** Vrai si toute cette plage est incluse dans {@code other}. */
  public boolean within(Ipv4Range other) {
    return prefix >= other.prefix && (network & mask(other.prefix)) == other.network;
  }

  /** Nombre d'adresses d'hôtes parcourues. */
  public long hostCount() {
    long size = 1L << (32 - prefix);
    return prefix >= 31 ? size : size - 2;
  }

  public List<String> hosts() {
    long size = 1L << (32 - prefix);
    long first = prefix >= 31 ? network : network + 1;
    long last = prefix >= 31 ? network + size - 1 : network + size - 2;
    List<String> hosts = new ArrayList<>();
    for (long a = first; a <= last; a++) {
      hosts.add(format(a));
    }
    return hosts;
  }

  static String format(long a) {
    return ((a >> 24) & 255) + "." + ((a >> 16) & 255) + "." + ((a >> 8) & 255) + "." + (a & 255);
  }

  @Override
  public String toString() {
    return format(network) + "/" + prefix;
  }
}

package sn.ept.transdic1.collection.service;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Plage d'adresses IPv4 ou IPv6 au format CIDR (192.0.2.0/24, 2001:db8::/32), pour restreindre les
 * adresses d'où un jeton d'inscription est accepté (ADR 0054).
 *
 * <p>Seules des adresses littérales sont analysées : une chaîne qui n'a pas la forme d'une adresse
 * est refusée avant d'atteindre {@link InetAddress}, qui ferait sinon une résolution DNS.
 */
public record AddressRange(byte[] network, int prefix) {
  private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
  private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:.]{2,45}");

  public static AddressRange parse(String cidr) {
    String value = cidr == null ? "" : cidr.trim();
    int slash = value.indexOf('/');
    String address = slash < 0 ? value : value.substring(0, slash);
    var bytes =
        literal(address)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "Plage invalide (format attendu : 192.0.2.0/24) : " + cidr));
    int max = bytes.length * 8;
    int prefix;
    try {
      prefix = slash < 0 ? max : Integer.parseInt(value.substring(slash + 1));
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Préfixe invalide : " + cidr);
    }
    if (prefix < 0 || prefix > max) {
      throw new IllegalArgumentException("Préfixe invalide : " + cidr);
    }
    if (!Arrays.equals(bytes, masked(bytes, prefix))) {
      throw new IllegalArgumentException("Adresse de réseau attendue pour " + cidr);
    }
    return new AddressRange(bytes, prefix);
  }

  /** Liste « a, b, c » de plages ; vide si la chaîne est vide ou nulle. */
  public static List<AddressRange> parseAll(String commaSeparated) {
    List<AddressRange> ranges = new ArrayList<>();
    if (commaSeparated == null || commaSeparated.isBlank()) {
      return ranges;
    }
    for (String part : commaSeparated.split(",")) {
      if (!part.isBlank()) {
        ranges.add(parse(part));
      }
    }
    return ranges;
  }

  /** Octets d'une adresse littérale IPv4 ou IPv6, sans jamais interroger le DNS. */
  public static Optional<byte[]> literal(String address) {
    if (address == null) {
      return Optional.empty();
    }
    String value = address.trim();
    boolean v4 = IPV4.matcher(value).matches();
    boolean v6 = !v4 && value.contains(":") && IPV6.matcher(value).matches();
    if (!v4 && !v6) {
      return Optional.empty();
    }
    // Un octet hors bornes (999.1.1.1) ferait traiter la chaîne comme un nom d'hôte, donc résoudre.
    if (v4 && Arrays.stream(value.split("\\.")).anyMatch(octet -> Integer.parseInt(octet) > 255)) {
      return Optional.empty();
    }
    try {
      return Optional.of(InetAddress.getByName(value).getAddress());
    } catch (UnknownHostException e) {
      return Optional.empty();
    }
  }

  /** Vrai si l'adresse littérale donnée appartient à la plage (même famille d'adresses). */
  public boolean contains(String address) {
    return literal(address)
        .filter(bytes -> bytes.length == network.length)
        .map(bytes -> Arrays.equals(masked(bytes, prefix), network))
        .orElse(false);
  }

  private static byte[] masked(byte[] address, int prefix) {
    byte[] result = address.clone();
    for (int i = 0; i < result.length; i++) {
      int bits = Math.max(0, Math.min(8, prefix - i * 8));
      result[i] = (byte) (result[i] & (bits == 0 ? 0 : (0xFF << (8 - bits)) & 0xFF));
    }
    return result;
  }

  @Override
  public String toString() {
    try {
      return InetAddress.getByAddress(network).getHostAddress() + "/" + prefix;
    } catch (UnknownHostException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof AddressRange range
        && prefix == range.prefix
        && Arrays.equals(network, range.network);
  }

  @Override
  public int hashCode() {
    return 31 * Arrays.hashCode(network) + prefix;
  }
}

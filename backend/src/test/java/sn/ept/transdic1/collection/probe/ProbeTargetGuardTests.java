package sn.ept.transdic1.collection.probe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class ProbeTargetGuardTests {

  /** Faux DNS : les noms connus sont résolus, les adresses littérales passent telles quelles. */
  private final Function<String, InetAddress[]> fakeDns =
      host -> {
        try {
          return switch (host) {
            case "evil.example" -> new InetAddress[] {InetAddress.getByName("127.0.0.1")};
            case "mapped-loopback.example" -> new InetAddress[] {mapped(127, 0, 0, 1)};
            case "mapped-metadata.example" -> new InetAddress[] {mapped(169, 254, 169, 254)};
            case "example.org" -> new InetAddress[] {InetAddress.getByName("93.184.216.34")};
            case "mixed.example" ->
                new InetAddress[] {
                  InetAddress.getByName("127.0.0.1"), InetAddress.getByName("93.184.216.34")
                };
            default -> InetAddress.getAllByName(host);
          };
        } catch (UnknownHostException e) {
          throw new IllegalStateException(e);
        }
      };

  /** Vraie Inet6Address IPv4-mappée (::ffff:a.b.c.d), que Java ne ramène pas à une IPv4. */
  private static InetAddress mapped(int a, int b, int c, int d) throws UnknownHostException {
    byte[] bytes = new byte[16];
    bytes[10] = (byte) 0xff;
    bytes[11] = (byte) 0xff;
    bytes[12] = (byte) a;
    bytes[13] = (byte) b;
    bytes[14] = (byte) c;
    bytes[15] = (byte) d;
    return Inet6Address.getByAddress(null, bytes, -1);
  }

  private final ProbeTargetGuard guard = new ProbeTargetGuard(fakeDns);

  private void assertRejected(String host) {
    assertThatThrownBy(() -> guard.resolve(host)).isInstanceOf(ProbeTargetException.class);
  }

  @Test
  void rejectsLoopbackV4() {
    assertRejected("127.0.0.1");
  }

  @Test
  void rejectsLoopbackV6() {
    assertRejected("::1");
  }

  @Test
  void rejectsIpv4MappedLoopback() {
    assertRejected("::ffff:127.0.0.1");
  }

  @Test
  void rejectsLinkLocalMetadata() {
    assertRejected("169.254.169.254");
  }

  @Test
  void rejectsMappedInet6AddressFromDns() {
    assertRejected("mapped-loopback.example");
    assertRejected("mapped-metadata.example");
  }

  @Test
  void rejectsIpv6LinkLocal() {
    assertRejected("fe80::1");
  }

  @Test
  void rejectsAnyLocal() {
    assertRejected("0.0.0.0");
  }

  @Test
  void rejectsMulticast() {
    assertRejected("224.0.0.1");
  }

  @Test
  void rejectsNameResolvingToLoopback() {
    assertRejected("evil.example");
  }

  @Test
  void rejectsHostStartingWithDash() {
    assertRejected("-c1");
  }

  @Test
  void rejectsBlank() {
    assertRejected("  ");
    assertRejected(null);
  }

  @Test
  void acceptsPrivateLan() {
    assertThat(guard.resolve("192.168.1.10").getHostAddress()).isEqualTo("192.168.1.10");
    assertThat(guard.resolve("10.0.0.5").getHostAddress()).isEqualTo("10.0.0.5");
  }

  @Test
  void acceptsPublicName() {
    assertThat(guard.resolve("example.org").getHostAddress()).isEqualTo("93.184.216.34");
  }

  @Test
  void skipsForbiddenAddressAndTakesNextAllowed() {
    assertThat(guard.resolve("mixed.example").getHostAddress()).isEqualTo("93.184.216.34");
  }
}

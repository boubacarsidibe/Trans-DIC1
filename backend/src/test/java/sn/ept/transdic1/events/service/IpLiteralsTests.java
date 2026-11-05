package sn.ept.transdic1.events.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Forme canonique des adresses IP littérales, sans résolution DNS. */
class IpLiteralsTests {

  @Test
  void ipv6EstCompresséeEnFormeCanonique() {
    assertThat(IpLiterals.canonical("fe80:0:0:0:0:0:0:1")).isEqualTo("fe80::1");
    assertThat(IpLiterals.canonical("FE80::1")).isEqualTo("fe80::1");
    assertThat(IpLiterals.canonical("0:0:0:0:0:0:0:1")).isEqualTo("::1");
    assertThat(IpLiterals.canonical("2001:db8:0:0:1:0:0:1")).isEqualTo("2001:db8::1:0:0:1");
    assertThat(IpLiterals.canonical("2001:0db8:0000:0000:0000:ff00:0042:8329"))
        .isEqualTo("2001:db8::ff00:42:8329");
    assertThat(IpLiterals.canonical("2001:db8:1:1:1:1:0:1")).isEqualTo("2001:db8:1:1:1:1:0:1");
    assertThat(IpLiterals.canonical("[2001:db8::1]")).isEqualTo("2001:db8::1");
  }

  @Test
  void lIndiceDeZoneEstIgnoré() {
    assertThat(IpLiterals.canonical("fe80:0:0:0:0:0:0:1%5")).isEqualTo("fe80::1");
    assertThat(IpLiterals.canonical("fe80::1%eth0")).isEqualTo("fe80::1");
  }

  @Test
  void ipv4MappéeEnIpv6EstDéballée() {
    assertThat(IpLiterals.canonical("::ffff:192.0.2.10")).isEqualTo("192.0.2.10");
    assertThat(IpLiterals.canonical("0:0:0:0:0:ffff:c000:20a")).isEqualTo("192.0.2.10");
  }

  @Test
  void ipv4EstNormalisée() {
    assertThat(IpLiterals.canonical(" 10.0.0.1 ")).isEqualTo("10.0.0.1");
    assertThat(IpLiterals.canonical("10.000.0.01")).isEqualTo("10.0.0.1");
  }

  @Test
  void unNomDHôteNEstJamaisRésoluEtResteTelQuel() {
    long start = System.nanoTime();
    assertThat(IpLiterals.canonical("Routeur-1.Exemple.invalid"))
        .isEqualTo("routeur-1.exemple.invalid");
    assertThat(IpLiterals.canonical("300.1.1.1")).isEqualTo("300.1.1.1");
    assertThat(IpLiterals.canonical("1:2:3")).isEqualTo("1:2:3");
    assertThat(IpLiterals.canonical(null)).isNull();
    assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(500);
  }
}

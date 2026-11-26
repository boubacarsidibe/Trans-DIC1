package sn.ept.transdic1.collection.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Plages d'adresses d'un jeton d'inscription (ADR 0054), IPv4 et IPv6, sans DNS. */
class AddressRangeTests {

  @ParameterizedTest
  @CsvSource({
    "192.0.2.0/24, 192.0.2.17, true",
    "192.0.2.0/24, 192.0.3.1, false",
    "198.51.100.0/25, 198.51.100.127, true",
    "198.51.100.0/25, 198.51.100.128, false",
    "0.0.0.0/0, 203.0.113.9, true",
    "192.0.2.5/32, 192.0.2.5, true",
    "192.0.2.5, 192.0.2.6, false",
    "2001:db8::/32, 2001:db8:1::5, true",
    "2001:db8::/32, 2001:db9::5, false",
    "2001:db8::/32, 192.0.2.1, false",
    "192.0.2.0/24, 2001:db8::1, false",
    "192.0.2.0/24, srv.example, false",
    "192.0.2.0/24, 999.0.2.1, false",
  })
  void containsOnlyAddressesOfTheRange(String cidr, String address, boolean expected) {
    assertThat(AddressRange.parse(cidr).contains(address)).isEqualTo(expected);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "192.0.2.1/24",
        "192.0.2.0/33",
        "2001:db8::/129",
        "intranet.example/24",
        "",
        "192.0.2.0/x",
        "300.0.0.0/8"
      })
  void refusesInvalidRanges(String cidr) {
    assertThatThrownBy(() -> AddressRange.parse(cidr)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void parsesAListAndPrintsCanonicalForms() {
    List<AddressRange> ranges = AddressRange.parseAll(" 192.0.2.0/24 ,2001:DB8:0::/32,, ");

    assertThat(ranges)
        .extracting(AddressRange::toString)
        .containsExactly("192.0.2.0/24", "2001:db8:0:0:0:0:0:0/32");
    assertThat(AddressRange.parseAll(null)).isEmpty();
    assertThat(AddressRange.parseAll("  ")).isEmpty();
    assertThat(ranges.get(0))
        .isEqualTo(AddressRange.parse("192.0.2.0/24"))
        .hasSameHashCodeAs(AddressRange.parse("192.0.2.0/24"));
    assertThat(ranges.get(0)).isNotEqualTo(ranges.get(1));
  }

  @Test
  void literalNeverResolvesNames() {
    assertThat(AddressRange.literal("localhost")).isEmpty();
    assertThat(AddressRange.literal(null)).isEmpty();
    assertThat(AddressRange.literal("192.0.2.1")).isPresent();
    assertThat(AddressRange.literal("2001:db8::zz")).isEmpty();
  }

  @Test
  void keepsOnlyAddressesThatDesignateTheMachine() {
    assertThat(
            AgentEnrollmentService.usableAddresses(
                List.of(
                    "127.0.0.1",
                    "::1",
                    "fe80::1",
                    "169.254.1.2",
                    "0.0.0.0",
                    "nom",
                    "192.0.2.4",
                    "192.0.2.4",
                    "2001:db8::4")))
        .containsExactly("192.0.2.4", "2001:db8:0:0:0:0:0:4");
    assertThat(AgentEnrollmentService.usableAddresses(null)).isEmpty();
  }
}

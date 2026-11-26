package sn.ept.transdic1.collection.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Limitation de débit des demandes d'inscription, par adresse (ADR 0054). */
class EnrollmentRateLimitFilterTests {

  private static MockHttpServletResponse call(EnrollmentRateLimitFilter filter, String address)
      throws Exception {
    var request = new MockHttpServletRequest("POST", EnrollmentSecurityConfig.ENROLL_PATH);
    request.setRemoteAddr(address);
    var response = new MockHttpServletResponse();
    filter.doFilter(request, response, new MockFilterChain());
    return response;
  }

  @Test
  void beyondTheThresholdTheAddressReceives429WithRetryAfter() throws Exception {
    var filter = new EnrollmentRateLimitFilter(2);

    assertThat(call(filter, "192.0.2.1").getStatus()).isEqualTo(200);
    assertThat(call(filter, "192.0.2.1").getStatus()).isEqualTo(200);
    var refused = call(filter, "192.0.2.1");

    assertThat(refused.getStatus()).isEqualTo(429);
    assertThat(refused.getHeader("Retry-After")).isNotBlank();
    assertThat(refused.getContentAsString()).contains("Trop de demandes d'inscription");
  }

  @Test
  void eachAddressHasItsOwnBudget() throws Exception {
    var filter = new EnrollmentRateLimitFilter(1);

    assertThat(call(filter, "192.0.2.1").getStatus()).isEqualTo(200);
    assertThat(call(filter, "192.0.2.2").getStatus()).isEqualTo(200);
    assertThat(call(filter, "192.0.2.1").getStatus()).isEqualTo(429);
  }

  @Test
  void aZeroOrNegativeThresholdStillLetsOneRequestThrough() throws Exception {
    var filter = new EnrollmentRateLimitFilter(0);

    assertThat(call(filter, "198.51.100.1").getStatus()).isEqualTo(200);
    assertThat(call(filter, "198.51.100.1").getStatus()).isEqualTo(429);
  }
}

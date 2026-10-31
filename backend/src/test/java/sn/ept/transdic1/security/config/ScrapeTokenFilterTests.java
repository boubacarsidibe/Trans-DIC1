package sn.ept.transdic1.security.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

/** Filtre du jeton de collecte des métriques. */
class ScrapeTokenFilterTests {
  private static final String TOKEN = "un-jeton-de-collecte-de-32-caracteres-au-moins";

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  private boolean authenticated(String configured, String header) throws Exception {
    SecurityContextHolder.clearContext();
    var request = new MockHttpServletRequest("GET", "/actuator/prometheus");
    if (header != null) {
      request.addHeader("Authorization", header);
    }
    new MetricsSecurityConfig.ScrapeTokenFilter(configured)
        .doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
    return SecurityContextHolder.getContext().getAuthentication() != null;
  }

  @Test
  void leBonJetonDonneLeRoleMetrics() throws Exception {
    assertThat(authenticated(TOKEN, "Bearer " + TOKEN)).isTrue();
    assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
        .extracting(Object::toString)
        .containsExactly("ROLE_METRICS");
  }

  @Test
  void unJetonAbsentFauxOuMalFormeEstRefuse() throws Exception {
    assertThat(authenticated(TOKEN, null)).isFalse();
    assertThat(authenticated(TOKEN, "Bearer " + TOKEN + "x")).isFalse();
    assertThat(authenticated(TOKEN, "Basic " + TOKEN)).isFalse();
  }

  @Test
  void unJetonConfigureTropCourtOuAbsentFermeLEndpoint() throws Exception {
    assertThat(authenticated("court", "Bearer court")).isFalse();
    assertThat(authenticated("", "Bearer ")).isFalse();
    assertThat(authenticated(null, "Bearer null")).isFalse();
  }
}

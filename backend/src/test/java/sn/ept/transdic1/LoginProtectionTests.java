package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import sn.ept.transdic1.security.domain.AppUser;
import sn.ept.transdic1.security.domain.Role;
import sn.ept.transdic1.security.repository.UserRepository;

class LoginProtectionTests {
  private static final String PASSWORD = "Mot-de-passe-test-123";
  private static final String UNIFORM_BODY = "{\"status\":401,\"error\":\"Identifiants invalides\",\"fields\":{}}";

  private static MockHttpServletRequestBuilder login(String username, String password) {
    return post("/api/v1/auth/login")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}");
  }

  @Nested
  @SpringBootTest
  @AutoConfigureMockMvc
  @ActiveProfiles("test")
  class SecurityHeaders {
    @Autowired private MockMvc mockMvc;

    @Test
    void apiResponsesCarryStrictHeaders() throws Exception {
      mockMvc
          .perform(get("/api/v1/equipements").secure(true))
          .andExpect(status().isUnauthorized())
          .andExpect(header().string("Content-Security-Policy", containsString("default-src 'none'")))
          .andExpect(header().string("Content-Security-Policy", containsString("frame-ancestors 'none'")))
          .andExpect(header().string("X-Content-Type-Options", "nosniff"))
          .andExpect(header().string("X-Frame-Options", "DENY"))
          .andExpect(header().string("Referrer-Policy", "no-referrer"))
          .andExpect(header().string("Permissions-Policy", containsString("camera=()")))
          .andExpect(header().string("Strict-Transport-Security", containsString("max-age=31536000")))
          .andExpect(header().string("Strict-Transport-Security", containsString("includeSubDomains")));
    }

    @Test
    void swaggerUiKeepsAWorkablePolicyWithoutOpeningTheApi() throws Exception {
      mockMvc
          .perform(get("/swagger-ui/index.html"))
          .andExpect(header().string("Content-Security-Policy", containsString("script-src 'self'")));
    }
  }

  @Nested
  @SpringBootTest(properties = "security.login.rate-limit.attempts=3")
  @AutoConfigureMockMvc
  @ActiveProfiles("test")
  class RateLimit {
    @Autowired private MockMvc mockMvc;

    @Test
    void blocksAfterTooManyAttemptsFromTheSameAddress() throws Exception {
      for (int i = 0; i < 3; i++) {
        mockMvc.perform(login("inconnu", "x")).andExpect(status().isUnauthorized());
      }
      mockMvc
          .perform(login("inconnu", "x"))
          .andExpect(status().isTooManyRequests())
          .andExpect(header().exists("Retry-After"));
    }

    @Test
    void doesNotLimitOtherEndpoints() throws Exception {
      for (int i = 0; i < 6; i++) {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
      }
    }
  }

  @Nested
  @SpringBootTest
  @AutoConfigureMockMvc
  @ActiveProfiles("test")
  class AccountLockout {
    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository users;
    @Autowired private PasswordEncoder encoder;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void prepare() {
      jdbc.update("DELETE FROM audit_entry");
      users.deleteAll();
      users.save(new AppUser("noc-test", "noc-test@example.test", encoder.encode(PASSWORD), Role.NOC_OPERATOR));
    }

    private String body(MockHttpServletRequestBuilder request) throws Exception {
      MvcResult result = mockMvc.perform(request).andReturn();
      assertThat(result.getResponse().getStatus()).isEqualTo(401);
      return result.getResponse().getContentAsString();
    }

    @Test
    void allFailureCausesLookIdentical() throws Exception {
      String wrongPassword = body(login("noc-test", "mauvais"));
      String unknownUser = body(login("personne", "mauvais"));
      assertThat(wrongPassword).isEqualTo(unknownUser).isEqualTo(UNIFORM_BODY);

      for (int i = 0; i < 4; i++) body(login("noc-test", "mauvais"));
      String lockedWithGoodPassword = body(login("noc-test", PASSWORD));
      assertThat(lockedWithGoodPassword).isEqualTo(UNIFORM_BODY);
    }

    @Test
    void locksTheAccountAfterFiveFailuresEvenWithTheRightPassword() throws Exception {
      for (int i = 0; i < 5; i++) body(login("noc-test", "mauvais"));

      mockMvc.perform(login("noc-test", PASSWORD)).andExpect(status().isUnauthorized());
      assertThat(users.findByUsername("noc-test").orElseThrow().isLocked(Instant.now())).isTrue();
    }

    @Test
    void unlocksOnceTheLockExpiresAndResetsTheCounter() throws Exception {
      for (int i = 0; i < 5; i++) body(login("noc-test", "mauvais"));
      jdbc.update(
          "UPDATE app_user SET locked_until = ? WHERE username = 'noc-test'",
          java.sql.Timestamp.from(Instant.now().minusSeconds(5)));

      mockMvc.perform(login("noc-test", PASSWORD)).andExpect(status().isOk());

      assertThat(users.findByUsername("noc-test").orElseThrow().getFailedLoginAttempts()).isZero();
    }

    @Test
    void aSuccessfulLoginResetsTheFailureCounter() throws Exception {
      for (int i = 0; i < 3; i++) body(login("noc-test", "mauvais"));
      mockMvc.perform(login("noc-test", PASSWORD)).andExpect(status().isOk());
      for (int i = 0; i < 3; i++) body(login("noc-test", "mauvais"));

      mockMvc.perform(login("noc-test", PASSWORD)).andExpect(status().isOk());
    }

    @Test
    void lockDurationGrowsWithRepeatedFailures() throws Exception {
      for (int i = 0; i < 5; i++) body(login("noc-test", "mauvais"));
      Instant first = lockedUntil();
      // Le compte reste verrouillé : on simule l'expiration puis un nouvel échec.
      jdbc.update("UPDATE app_user SET locked_until = ? WHERE username = 'noc-test'",
          java.sql.Timestamp.from(Instant.now().minusSeconds(1)));
      body(login("noc-test", "mauvais"));
      Instant second = lockedUntil();

      assertThat(second).isAfter(first.plusSeconds(30));
    }

    private Instant lockedUntil() {
      return jdbc.queryForObject(
              "SELECT locked_until FROM app_user WHERE username = 'noc-test'",
              java.sql.Timestamp.class)
          .toInstant();
    }

    @Test
    void securityEventsAreAuditedWithoutSecrets() throws Exception {
      body(login("personne", "secret-tape-par-erreur"));
      for (int i = 0; i < 5; i++) body(login("noc-test", "mauvais-mot-de-passe"));

      List<String> rows =
          jdbc.query(
              "SELECT action || '|' || COALESCE(username,'') || '|' || COALESCE(details_sanitized,'') FROM audit_entry",
              (rs, n) -> rs.getString(1));
      assertThat(rows).anyMatch(r -> r.startsWith("LOGIN_FAILED_UNKNOWN||"));
      assertThat(rows).anyMatch(r -> r.startsWith("ACCOUNT_LOCKED|noc-test|"));
      assertThat(String.join("\n", rows))
          .doesNotContain("mauvais-mot-de-passe")
          .doesNotContain("secret-tape-par-erreur")
          .doesNotContain("personne");
    }
  }
}

package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import sn.ept.transdic1.security.domain.AppUser;
import sn.ept.transdic1.security.domain.Role;
import sn.ept.transdic1.security.repository.UserRepository;
import sn.ept.transdic1.security.service.SessionCleanupService;

/** Limitation de débit de /refresh et nettoyage des sessions expirées (ADR-0005). */
class SessionHardeningTests {

  @Nested
  @SpringBootTest(properties = "security.login.rate-limit.attempts=2")
  @AutoConfigureMockMvc
  @ActiveProfiles("test")
  class RefreshRateLimit {
    @Autowired private MockMvc mockMvc;

    @Test
    void refreshIsLimitedPerAddressAndAnswersRetryAfter() throws Exception {
      // Limite de renouvellement = 3 × la limite de connexion = 6 par fenêtre.
      for (int i = 0; i < 6; i++) {
        mockMvc
            .perform(
                post("/api/v1/auth/refresh")
                    .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                    .cookie(new Cookie("trans_dic1_refresh", "inconnu-" + i)))
            .andExpect(status().isUnauthorized());
      }

      mockMvc
          .perform(
              post("/api/v1/auth/refresh")
                  .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                  .cookie(new Cookie("trans_dic1_refresh", "inconnu-7")))
          .andExpect(status().isTooManyRequests())
          .andExpect(header().exists(HttpHeaders.RETRY_AFTER));
    }
  }

  @Nested
  @SpringBootTest
  @ActiveProfiles("test")
  class Cleanup {
    @Autowired private SessionCleanupService cleanup;
    @Autowired private UserRepository users;
    @Autowired private PasswordEncoder encoder;
    @Autowired private JdbcTemplate jdbc;

    private void insertSession(UUID userId, String hash, String createdAgo, String expiresIn) {
      UUID id = UUID.randomUUID();
      jdbc.update(
          "INSERT INTO user_session(id, user_id, token_hash, created_at, expires_at, family_id)"
              + " VALUES (?, ?, ?, now() - ?::interval, now() + ?::interval, ?)",
          id,
          userId,
          hash,
          createdAgo,
          expiresIn,
          id);
    }

    @Test
    void removesSessionsExpiredLongAgoAndKeepsTheOthers() {
      AppUser user =
          users.saveAndFlush(
              new AppUser(
                  "clean-" + UUID.randomUUID().toString().substring(0, 8),
                  UUID.randomUUID() + "@example.test",
                  encoder.encode("x"),
                  Role.VIEWER));
      String old = "old-" + UUID.randomUUID();
      String recent = "recent-" + UUID.randomUUID();
      String active = "active-" + UUID.randomUUID();
      insertSession(user.getId(), old, "20 days", "-15 days");
      insertSession(user.getId(), recent, "3 days", "-1 day");
      insertSession(user.getId(), active, "1 hour", "7 hours");

      cleanup.purgeExpiredSessions();

      assertThat(count(old)).isZero();
      assertThat(count(recent)).isEqualTo(1);
      assertThat(count(active)).isEqualTo(1);
      users.deleteById(user.getId());
    }

    private int count(String hash) {
      return jdbc.queryForObject(
          "SELECT count(*) FROM user_session WHERE token_hash = ?", Integer.class, hash);
    }
  }
}

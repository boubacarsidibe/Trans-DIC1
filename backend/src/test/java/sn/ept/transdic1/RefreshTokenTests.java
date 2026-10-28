package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import sn.ept.transdic1.security.domain.AppUser;
import sn.ept.transdic1.security.domain.Role;
import sn.ept.transdic1.security.repository.UserRepository;

/**
 * Renouvellement de jeton par cookie HttpOnly avec rotation et détection de réutilisation
 * (ADR-0005).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RefreshTokenTests {
  private static final String PASSWORD = "Mot-de-passe-test-123";
  private static final String ORIGIN = "http://localhost:5173";
  private static final String COOKIE = "trans_dic1_refresh";

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private PasswordEncoder encoder;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void prepareUser() {
    users.deleteAll();
    users.save(
        new AppUser(
            "refresh-user",
            "refresh-user@example.test",
            encoder.encode(PASSWORD),
            Role.NOC_OPERATOR));
  }

  private MvcResult login() throws Exception {
    return mockMvc
        .perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"refresh-user\",\"password\":\"" + PASSWORD + "\"}"))
        .andExpect(status().isOk())
        .andReturn();
  }

  private static String refreshValue(MvcResult result) {
    String header = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
    assertThat(header).startsWith(COOKIE + "=");
    return header.substring((COOKIE + "=").length(), header.indexOf(';'));
  }

  private MvcResult refresh(String value) throws Exception {
    return mockMvc
        .perform(
            post("/api/v1/auth/refresh")
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .cookie(new Cookie(COOKIE, value)))
        .andReturn();
  }

  @Test
  void loginSetsAHardenedRefreshCookieAndNeverReturnsItInTheBody() throws Exception {
    MvcResult result = login();

    String header = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
    assertThat(header)
        .contains("HttpOnly")
        .contains("Secure")
        .contains("SameSite=Strict")
        .contains("Path=/api/v1/auth")
        .contains("Max-Age=");
    assertThat(result.getResponse().getContentAsString()).doesNotContain(refreshValue(result));
    assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
  }

  @Test
  void onlyTheHashOfTheRefreshTokenIsStored() throws Exception {
    String value = refreshValue(login());

    Integer rawMatches =
        jdbc.queryForObject(
            "SELECT count(*) FROM user_session WHERE token_hash = ?", Integer.class, value);
    assertThat(rawMatches).isZero();
  }

  @Test
  void refreshRotatesTheTokenAndReturnsANewAccessToken() throws Exception {
    String first = refreshValue(login());

    MvcResult second = refresh(first);

    assertThat(second.getResponse().getStatus()).isEqualTo(200);
    assertThat(second.getResponse().getContentAsString()).contains("accessToken");
    assertThat(refreshValue(second)).isNotEqualTo(first);
  }

  @Test
  void reusingAReplacedTokenRevokesTheWholeFamily() throws Exception {
    String first = refreshValue(login());
    String second = refreshValue(refresh(first));

    // Rejouer le premier jeton (déjà remplacé) : refus, et la connexion entière est révoquée.
    assertThat(refresh(first).getResponse().getStatus()).isEqualTo(401);
    assertThat(refresh(second).getResponse().getStatus()).isEqualTo(401);

    Integer active =
        jdbc.queryForObject(
            "SELECT count(*) FROM user_session WHERE revoked_at IS NULL", Integer.class);
    assertThat(active).isZero();
    Integer audited =
        jdbc.queryForObject(
            "SELECT count(*) FROM audit_entry WHERE action = 'REFRESH_REUSE_DETECTED'",
            Integer.class);
    assertThat(audited).isPositive();
  }

  @Test
  void refreshWithoutCookieOrWithUnknownTokenIsRefusedAndClearsTheCookie() throws Exception {
    mockMvc
        .perform(post("/api/v1/auth/refresh").header(HttpHeaders.ORIGIN, ORIGIN))
        .andExpect(status().isUnauthorized());

    MvcResult unknown = refresh("inconnu");
    assertThat(unknown.getResponse().getStatus()).isEqualTo(401);
    assertThat(unknown.getResponse().getHeader(HttpHeaders.SET_COOKIE)).contains("Max-Age=0");
  }

  @Test
  void refreshRequiresAnAllowedOrigin() throws Exception {
    String value = refreshValue(login());

    mockMvc
        .perform(post("/api/v1/auth/refresh").cookie(new Cookie(COOKIE, value)))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            post("/api/v1/auth/refresh")
                .header(HttpHeaders.ORIGIN, "https://evil.example")
                .cookie(new Cookie(COOKIE, value)))
        .andExpect(status().isForbidden());
  }

  @Test
  void logoutRevokesTheConnectionAndClearsTheCookie() throws Exception {
    String value = refreshValue(login());

    MvcResult out =
        mockMvc
            .perform(
                post("/api/v1/auth/logout")
                    .header(HttpHeaders.ORIGIN, ORIGIN)
                    .cookie(new Cookie(COOKIE, value)))
            .andExpect(status().isNoContent())
            .andReturn();

    assertThat(out.getResponse().getHeader(HttpHeaders.SET_COOKIE)).contains("Max-Age=0");
    assertThat(refresh(value).getResponse().getStatus()).isEqualTo(401);
  }

  @Test
  void anExpiredConnectionCannotBeRefreshed() throws Exception {
    String value = refreshValue(login());
    jdbc.update(
        "UPDATE user_session SET created_at = now() - interval '2 hours',"
            + " expires_at = now() - interval '1 hour'");

    assertThat(refresh(value).getResponse().getStatus()).isEqualTo(401);
  }

  @Test
  void accessTokenResponseStillWorksForLogin() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"refresh-user\",\"password\":\"" + PASSWORD + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tokenType").value("Bearer"))
        .andExpect(jsonPath("$.role").value("NOC_OPERATOR"));
  }
}

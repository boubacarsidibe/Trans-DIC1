package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import sn.ept.transdic1.security.domain.AppUser;
import sn.ept.transdic1.security.domain.Role;
import sn.ept.transdic1.security.repository.UserRepository;
import sn.ept.transdic1.security.service.UserAdminService;

/** Gestion des comptes : droits, garde-fous, révocation des connexions et audit. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class UserAdminApiTests {
  private static final String PASSWORD = "Mot-de-passe-test-123";
  private static final String BASE = "/api/v1/utilisateurs";

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private PasswordEncoder encoder;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EntityManager em;
  @Autowired private UserAdminService service;

  private String suffix;
  private AppUser admin;
  private AppUser noc;
  private AppUser viewer;
  private String adminJwt;

  @BeforeEach
  void data() throws Exception {
    suffix = UUID.randomUUID().toString().substring(0, 8);
    admin = save("adm", Role.ADMIN);
    noc = save("noc", Role.NOC_OPERATOR);
    viewer = save("vwr", Role.VIEWER);
    adminJwt = login(admin.getUsername(), PASSWORD).andReturn().getResponse().getContentAsString();
    adminJwt = JsonPath.read(adminJwt, "$.accessToken");
  }

  private AppUser save(String prefix, Role role) {
    return users.save(
        new AppUser(
            prefix + "-" + suffix,
            prefix + "-" + suffix + "@example.test",
            encoder.encode(PASSWORD),
            role));
  }

  private ResultActions login(String username, String password) throws Exception {
    return mockMvc.perform(
        post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"));
  }

  private ResultActions admin(String url, String body) throws Exception {
    return mockMvc.perform(
        post(url)
            .header("Authorization", "Bearer " + adminJwt)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  private static String newUser(String username, String password, String role) {
    return "{\"username\":\""
        + username
        + "\",\"email\":\""
        + username
        + "@example.test\",\"password\":\""
        + password
        + "\",\"role\":\""
        + role
        + "\"}";
  }

  private int auditCount(String action) {
    em.flush();
    return jdbc.queryForObject(
        "SELECT count(*) FROM audit_entry WHERE action = ? AND username = ?",
        Integer.class,
        action,
        admin.getUsername());
  }

  @Test
  void onlyAdministratorsManageAccounts() throws Exception {
    for (AppUser other : new AppUser[] {noc, viewer}) {
      String jwt =
          JsonPath.read(
              login(other.getUsername(), PASSWORD).andReturn().getResponse().getContentAsString(),
              "$.accessToken");
      mockMvc
          .perform(get(BASE).header("Authorization", "Bearer " + jwt))
          .andExpect(status().isForbidden());
      mockMvc
          .perform(
              post(BASE)
                  .header("Authorization", "Bearer " + jwt)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(newUser("intrus-" + suffix, "Un-mot-de-passe-long-1", "ADMIN")))
          .andExpect(status().isForbidden());
    }
    mockMvc.perform(get(BASE)).andExpect(status().isUnauthorized());
  }

  @Test
  void listNeverExposesPasswordsOrHashes() throws Exception {
    var body =
        mockMvc
            .perform(get(BASE).header("Authorization", "Bearer " + adminJwt))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].username").exists())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(body).doesNotContain("password").doesNotContain("$2a$").doesNotContain("hash");
  }

  @Test
  void createdAccountsCanLogInAndAreAudited() throws Exception {
    String name = "nouveau-" + suffix;

    admin(BASE, newUser(name, "Un-mot-de-passe-long-1", "NOC_OPERATOR"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.username").value(name))
        .andExpect(jsonPath("$.role").value("NOC_OPERATOR"))
        .andExpect(jsonPath("$.active").value(true))
        .andExpect(jsonPath("$.password").doesNotExist());

    login(name, "Un-mot-de-passe-long-1").andExpect(status().isOk());
    assertThat(auditCount("USER_CREATED")).isEqualTo(1);
  }

  @Test
  void invalidOrDuplicateAccountsAreRejected() throws Exception {
    admin(BASE, newUser("court-" + suffix, "trop-court", "VIEWER"))
        .andExpect(status().isBadRequest());
    admin(BASE, newUser("ab", "Un-mot-de-passe-long-1", "VIEWER"))
        .andExpect(status().isBadRequest());
    admin(BASE, newUser("espace " + suffix, "Un-mot-de-passe-long-1", "VIEWER"))
        .andExpect(status().isBadRequest());
    admin(BASE, newUser("pwd-" + suffix, "xxpwd-" + suffix + "xx-ok", "VIEWER"))
        .andExpect(status().isBadRequest());
    admin(BASE, newUser("role-" + suffix, "Un-mot-de-passe-long-1", "ROOT"))
        .andExpect(status().isBadRequest());
    admin(BASE, newUser(noc.getUsername(), "Un-mot-de-passe-long-1", "VIEWER"))
        .andExpect(status().isConflict());
  }

  @Test
  void changingARoleRevokesTheConnectionsOfThatUser() throws Exception {
    login(noc.getUsername(), PASSWORD).andExpect(status().isOk());
    em.flush();
    assertThat(activeSessions(noc)).isEqualTo(1);

    admin(BASE + "/" + noc.getId() + "/role", "{\"role\":\"ADMIN\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.role").value("ADMIN"));

    assertThat(activeSessions(noc)).isZero();
    assertThat(auditCount("USER_ROLE_CHANGED")).isEqualTo(1);
  }

  @Test
  void anAdministratorCannotChangeOrDeactivateItself() throws Exception {
    admin(BASE + "/" + admin.getId() + "/role", "{\"role\":\"VIEWER\"}")
        .andExpect(status().isConflict());
    admin(BASE + "/" + admin.getId() + "/activation", "{\"active\":false}")
        .andExpect(status().isConflict());
  }

  @Test
  void theLastActiveAdministratorIsProtected() {
    users.findAll().stream()
        .filter(u -> u.getRole() == Role.ADMIN && !u.getId().equals(admin.getId()))
        .forEach(
            other -> {
              other.setActive(false);
              users.save(other);
            });
    users.flush();

    assertThatThrownBy(() -> service.changeRole(admin.getId(), Role.VIEWER, "autre-admin"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("au moins un administrateur actif");
    assertThatThrownBy(() -> service.setActive(admin.getId(), false, "autre-admin"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("au moins un administrateur actif");
  }

  @Test
  void deactivatedAccountsCannotLogInUntilReactivated() throws Exception {
    login(viewer.getUsername(), PASSWORD).andExpect(status().isOk());

    admin(BASE + "/" + viewer.getId() + "/activation", "{\"active\":false}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.active").value(false));
    assertThat(activeSessions(viewer)).isZero();
    login(viewer.getUsername(), PASSWORD).andExpect(status().isUnauthorized());

    admin(BASE + "/" + viewer.getId() + "/activation", "{\"active\":true}")
        .andExpect(status().isOk());
    login(viewer.getUsername(), PASSWORD).andExpect(status().isOk());
    assertThat(auditCount("USER_DEACTIVATED")).isEqualTo(1);
    assertThat(auditCount("USER_ACTIVATED")).isEqualTo(1);
  }

  @Test
  void resettingAPasswordReplacesItUnlocksTheAccountAndRevokesConnections() throws Exception {
    for (int i = 0; i < 5; i++) {
      login(viewer.getUsername(), "mauvais-" + i).andExpect(status().isUnauthorized());
    }
    // Verrouillé : même le bon mot de passe est refusé.
    login(viewer.getUsername(), PASSWORD).andExpect(status().isUnauthorized());

    admin(
            BASE + "/" + viewer.getId() + "/mot-de-passe",
            "{\"password\":\"Nouveau-mot-de-passe-9\"}")
        .andExpect(status().isNoContent());

    login(viewer.getUsername(), PASSWORD).andExpect(status().isUnauthorized());
    login(viewer.getUsername(), "Nouveau-mot-de-passe-9").andExpect(status().isOk());
    assertThat(auditCount("USER_PASSWORD_RESET")).isEqualTo(1);
    var details =
        jdbc.queryForList(
            "SELECT details_sanitized FROM audit_entry WHERE action = 'USER_PASSWORD_RESET'",
            String.class);
    assertThat(details).allSatisfy(d -> assertThat(d).doesNotContain("Nouveau-mot-de-passe"));
  }

  @Test
  void unknownAccountsAreNotFound() throws Exception {
    admin(BASE + "/" + UUID.randomUUID() + "/role", "{\"role\":\"VIEWER\"}")
        .andExpect(status().isNotFound());
    admin(BASE + "/" + UUID.randomUUID() + "/activation", "{\"active\":false}")
        .andExpect(status().isNotFound());
    admin(
            BASE + "/" + UUID.randomUUID() + "/mot-de-passe",
            "{\"password\":\"Nouveau-mot-de-passe-9\"}")
        .andExpect(status().isNotFound());
  }

  private int activeSessions(AppUser user) {
    em.flush();
    return jdbc.queryForObject(
        "SELECT count(*) FROM user_session WHERE user_id = ? AND revoked_at IS NULL",
        Integer.class,
        user.getId());
  }
}

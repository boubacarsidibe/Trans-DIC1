package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
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
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.security.domain.AppUser;
import sn.ept.transdic1.security.domain.Role;
import sn.ept.transdic1.security.repository.UserRepository;

/** Journal d'audit : droits, filtres, export CSV sûr et lecture seule. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AuditApiTests {
  private static final String PASSWORD = "Mot-de-passe-test-123";
  private static final String BASE = "/api/v1/audit";

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private PasswordEncoder encoder;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private jakarta.persistence.EntityManager em;

  private String action;
  private UUID target;
  private String admin;
  private String noc;

  @BeforeEach
  void data() throws Exception {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    users.save(
        new AppUser(
            "adm-" + suffix,
            "adm-" + suffix + "@example.test",
            encoder.encode(PASSWORD),
            Role.ADMIN));
    users.save(
        new AppUser(
            "noc-" + suffix,
            "noc-" + suffix + "@example.test",
            encoder.encode(PASSWORD),
            Role.NOC_OPERATOR));
    admin = login("adm-" + suffix);
    noc = login("noc-" + suffix);
    action = "TEST_" + suffix.toUpperCase();
    target = UUID.randomUUID();
    insert(action, "=SOMME(A1)", "2026-10-01 10:00:00+00");
    insert(action, "détail normal", "2026-10-02 10:00:00+00");
    insert("AUTRE_" + suffix, "hors filtre", "2026-10-03 10:00:00+00");
  }

  private void insert(String act, String details, String at) {
    jdbc.update(
        "INSERT INTO audit_entry(id, username, action, target_type, target_id, correlation_id,"
            + " details_sanitized, performed_at) VALUES (?, 'alice', ?, 'SITE', ?, 'corr-1', ?,"
            + " ?::timestamptz)",
        UUID.randomUUID(),
        act,
        target,
        details,
        at);
  }

  private String login(String username) throws Exception {
    var body =
        mockMvc
            .perform(
                post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(body, "$.accessToken");
  }

  private org.springframework.test.web.servlet.ResultActions getAs(String jwt, String url)
      throws Exception {
    return mockMvc.perform(get(url).header("Authorization", "Bearer " + jwt));
  }

  @Test
  void seulsLesAdministrateursConsultentLeJournal() throws Exception {
    mockMvc.perform(get(BASE)).andExpect(status().isUnauthorized());
    getAs(noc, BASE).andExpect(status().isForbidden());
    getAs(noc, BASE + "/export").andExpect(status().isForbidden());
    getAs(admin, BASE).andExpect(status().isOk());
  }

  @Test
  void filtreParActionCibleEtPeriode() throws Exception {
    getAs(admin, BASE + "?action=" + action + "&targetId=" + target)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(2))
        .andExpect(jsonPath("$.content[0].details").value("détail normal"))
        .andExpect(jsonPath("$.content[0].username").value("alice"))
        .andExpect(jsonPath("$.content[0].correlationId").value("corr-1"));

    getAs(admin, BASE + "?action=" + action + "&from=2026-10-02T00:00:00Z&to=2026-10-03T00:00:00Z")
        .andExpect(jsonPath("$.content.length()").value(1));
    getAs(admin, BASE + "?action=" + action + "&username=ALICE")
        .andExpect(jsonPath("$.content.length()").value(2));
    getAs(admin, BASE + "?action=" + action + "&username=bob")
        .andExpect(jsonPath("$.content.length()").value(0));
    getAs(admin, BASE + "/actions")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@ == '" + action + "')]").exists());
  }

  @Test
  void exportCsvNeutraliseLesFormulesEtJournaliseLExport() throws Exception {
    String csv =
        getAs(admin, BASE + "/export?action=" + action)
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

    assertThat(csv).contains("Date (UTC)").contains("alice").contains("'=SOMME(A1)");
    assertThat(csv).doesNotContain(";=SOMME");
    em.flush();
    Integer exports =
        jdbc.queryForObject(
            "SELECT count(*) FROM audit_entry WHERE action = 'AUDIT_EXPORTED'", Integer.class);
    assertThat(exports).isGreaterThanOrEqualTo(1);
  }

  @Test
  void leJournalNePeutPasEtreModifie() throws Exception {
    for (var method :
        new org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder[] {
          put(BASE), delete(BASE), post(BASE)
        }) {
      mockMvc
          .perform(method.header("Authorization", "Bearer " + admin))
          .andExpect(status().is4xxClientError());
    }
    // En dernier : l'erreur SQL attendue rend la transaction du test inutilisable, or chaque
    // requête authentifiée relit l'état du compte en base (FEAT-01).
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE audit_entry SET details_sanitized = 'x' WHERE action = ?", action))
        .hasMessageContaining("lecture seule");
  }
}

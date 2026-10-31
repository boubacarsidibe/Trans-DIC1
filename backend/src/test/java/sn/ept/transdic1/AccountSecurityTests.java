package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.test.web.servlet.ResultActions;
import sn.ept.transdic1.security.domain.AppUser;
import sn.ept.transdic1.security.domain.Role;
import sn.ept.transdic1.security.repository.UserRepository;

/**
 * Comptes (FEAT-01) : mot de passe temporaire à changer à la première connexion, refus immédiat
 * d'un compte désactivé ou dont le rôle a changé, traces d'audit.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountSecurityTests {
  private static final String ADMIN_PASSWORD = "Mot-de-passe-admin-123";
  private static final String TEMPORARY = "Temporaire-Initial-2026";
  private static final String CHOSEN = "Choisi-Par-Moi-2026!";

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private PasswordEncoder encoder;
  @Autowired private JdbcTemplate jdbc;

  private String admin;
  private String suffix;

  @BeforeEach
  void data() throws Exception {
    suffix = UUID.randomUUID().toString().substring(0, 8);
    users.save(
        new AppUser(
            "acc-adm-" + suffix,
            suffix + "a@example.test",
            encoder.encode(ADMIN_PASSWORD),
            Role.ADMIN));
    admin = token(login("acc-adm-" + suffix, ADMIN_PASSWORD).andExpect(status().isOk()));
  }

  private ResultActions login(String username, String password) throws Exception {
    return mockMvc.perform(
        post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"));
  }

  private static String token(ResultActions result) throws Exception {
    return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.accessToken");
  }

  /** Compte créé par l'administrateur, avec un mot de passe qu'il a choisi. Renvoie son id. */
  private String createByAdmin(String username, String role) throws Exception {
    String body =
        mockMvc
            .perform(
                post("/api/v1/utilisateurs")
                    .header("Authorization", "Bearer " + admin)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"username\":\""
                            + username
                            + "\",\"email\":\""
                            + username
                            + "@example.test\",\"password\":\""
                            + TEMPORARY
                            + "\",\"role\":\""
                            + role
                            + "\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(body, "$.id");
  }

  private ResultActions changePassword(String jwt, String current, String next) throws Exception {
    return mockMvc.perform(
        post("/api/v1/auth/mot-de-passe")
            .header("Authorization", "Bearer " + jwt)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"" + current + "\",\"newPassword\":\"" + next + "\"}"));
  }

  @Test
  void leMotDePasseTemporaireDoitEtreChangeAvantToutAutreUsage() throws Exception {
    String username = "acc-noc-" + suffix;
    createByAdmin(username, "NOC_OPERATOR");

    ResultActions first =
        login(username, TEMPORARY)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.passwordChangeRequired").value(true));
    String jwt = token(first);

    mockMvc
        .perform(get("/api/v1/equipements").header("Authorization", "Bearer " + jwt))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));

    changePassword(jwt, "mauvais-mot-de-passe-actuel", CHOSEN).andExpect(status().isBadRequest());
    changePassword(jwt, TEMPORARY, TEMPORARY).andExpect(status().isBadRequest());
    changePassword(jwt, TEMPORARY, "court").andExpect(status().isBadRequest());
    changePassword(jwt, TEMPORARY, "Contient-" + username + "-123")
        .andExpect(status().isBadRequest());
    changePassword(jwt, TEMPORARY, CHOSEN).andExpect(status().isNoContent());

    mockMvc
        .perform(get("/api/v1/equipements").header("Authorization", "Bearer " + jwt))
        .andExpect(status().isOk());
    login(username, TEMPORARY).andExpect(status().isUnauthorized());
    login(username, CHOSEN)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.passwordChangeRequired").value(false));
    assertThat(
            jdbc.queryForList(
                "SELECT action FROM audit_entry WHERE username = ? ORDER BY performed_at",
                String.class,
                username))
        .contains("PASSWORD_CHANGE_FAILED", "PASSWORD_CHANGED");
  }

  @Test
  void uneReinitialisationParLAdministrateurRendLeMotDePasseDeNouveauTemporaire() throws Exception {
    String username = "acc-view-" + suffix;
    String id = createByAdmin(username, "VIEWER");
    changePassword(token(login(username, TEMPORARY)), TEMPORARY, CHOSEN)
        .andExpect(status().isNoContent());

    mockMvc
        .perform(
            post("/api/v1/utilisateurs/" + id + "/mot-de-passe")
                .header("Authorization", "Bearer " + admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"Nouveau-Temporaire-2026\"}"))
        .andExpect(status().is2xxSuccessful());

    login(username, "Nouveau-Temporaire-2026")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.passwordChangeRequired").value(true));
  }

  @Test
  void unCompteDesactiveEstRefuseImmediatementMemeAvecUnJetonValide() throws Exception {
    String username = "acc-off-" + suffix;
    String id = createByAdmin(username, "VIEWER");
    String jwt = token(login(username, TEMPORARY));
    changePassword(jwt, TEMPORARY, CHOSEN).andExpect(status().isNoContent());
    mockMvc
        .perform(get("/api/v1/equipements").header("Authorization", "Bearer " + jwt))
        .andExpect(status().isOk());

    mockMvc
        .perform(
            post("/api/v1/utilisateurs/" + id + "/activation")
                .header("Authorization", "Bearer " + admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":false}"))
        .andExpect(status().isOk());

    mockMvc
        .perform(get("/api/v1/equipements").header("Authorization", "Bearer " + jwt))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void unChangementDeRoleInvalideLesJetonsEnCours() throws Exception {
    String username = "acc-role-" + suffix;
    String id = createByAdmin(username, "NOC_OPERATOR");
    String jwt = token(login(username, TEMPORARY));
    changePassword(jwt, TEMPORARY, CHOSEN).andExpect(status().isNoContent());

    mockMvc
        .perform(
            post("/api/v1/utilisateurs/" + id + "/role")
                .header("Authorization", "Bearer " + admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"VIEWER\"}"))
        .andExpect(status().isOk());

    mockMvc
        .perform(get("/api/v1/equipements").header("Authorization", "Bearer " + jwt))
        .andExpect(status().isUnauthorized());
    login(username, CHOSEN)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.role").value("VIEWER"));
  }

  @Test
  void unCompteSansMotDePasseTemporaireNEstPasConcerne() throws Exception {
    mockMvc
        .perform(get("/api/v1/equipements").header("Authorization", "Bearer " + admin))
        .andExpect(status().isOk());
    login("acc-adm-" + suffix, ADMIN_PASSWORD)
        .andExpect(jsonPath("$.passwordChangeRequired").value(false));
  }
}

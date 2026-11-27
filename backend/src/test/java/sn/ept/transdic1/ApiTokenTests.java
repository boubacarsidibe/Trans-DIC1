package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.security.domain.AppUser;
import sn.ept.transdic1.security.domain.Role;
import sn.ept.transdic1.security.repository.UserRepository;
import sn.ept.transdic1.security.service.ApiTokenService;
import sn.ept.transdic1.security.service.JwtService;

/**
 * Jetons d'API de bout en bout : création montrée une fois, usage à la place d'un jeton de
 * connexion, droits du propriétaire, révocation, compte désactivé, et cloisonnement entre comptes.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ApiTokenTests {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private PasswordEncoder encoder;
  @Autowired private JwtService jwt;
  @Autowired private ApiTokenService tokens;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private jakarta.persistence.EntityManager em;

  private AppUser owner;
  private String ownerJwt;

  @BeforeEach
  void data() {
    owner = account("jeton-adm", Role.ADMIN);
    ownerJwt = jwt.issue(owner);
  }

  private AppUser account(String prefix, Role role) {
    String unique = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    return users.saveAndFlush(
        new AppUser(unique, unique + "@example.test", encoder.encode("MotDePasse-123456"), role));
  }

  private MvcResult call(MockHttpServletRequestBuilder request, String bearer) throws Exception {
    return mockMvc
        .perform(
            request
                .header("Authorization", "Bearer " + bearer)
                .contentType(MediaType.APPLICATION_JSON))
        .andReturn();
  }

  private JsonNode createToken(String name, Integer days) throws Exception {
    String body =
        days == null
            ? "{\"name\":\"" + name + "\"}"
            : "{\"name\":\"" + name + "\",\"days\":" + days + "}";
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/jetons")
                    .header("Authorization", "Bearer " + ownerJwt)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn();
    return JSON.readTree(result.getResponse().getContentAsString());
  }

  @Test
  void leSecretNEstMontreQuALaCreationEtOuvreLApi() throws Exception {
    JsonNode created = createToken("Tableau Grafana", null);
    String secret = created.get("secret").asText();

    assertThat(secret).startsWith("tdic_").hasSizeGreaterThan(40);
    assertThat(created.get("token").get("prefix").asText()).isEqualTo(secret.substring(0, 12));

    // La liste ne rend jamais le secret.
    mockMvc
        .perform(get("/api/v1/jetons").header("Authorization", "Bearer " + ownerJwt))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].name").value("Tableau Grafana"))
        .andExpect(jsonPath("$[0].secret").doesNotExist());

    // Le jeton remplace un jeton de connexion sur une requête ordinaire.
    assertThat(call(get("/api/v1/alertes"), secret).getResponse().getStatus()).isEqualTo(200);
  }

  @Test
  void unJetonNAPasPlusDeDroitsQueSonProprietaire() throws Exception {
    String secret = createToken("Lecture seule", null).get("secret").asText();
    assertThat(call(get("/api/v1/utilisateurs"), secret).getResponse().getStatus()).isEqualTo(200);

    // Le rôle est relu à chaque requête : un administrateur devenu lecteur perd son jeton.
    em.flush();
    jdbc.update("UPDATE app_user SET role = 'VIEWER' WHERE id = ?", owner.getId());
    em.clear();
    assertThat(call(get("/api/v1/alertes"), secret).getResponse().getStatus()).isEqualTo(403);
  }

  @Test
  void lesJetonsDUnOperateurOuDUnLecteurSontRefusesALUsageEtTraces() throws Exception {
    // Jetons créés avant l'ADR 0058 : ils ne sont pas révoqués, mais refusés.
    for (Role role : new Role[] {Role.NOC_OPERATOR, Role.VIEWER}) {
      AppUser user = account("jeton-ancien", role);
      String secret = tokens.create(user.getUsername(), "Ancien script", null).secret();
      em.flush();

      MvcResult refused = call(get("/api/v1/alertes"), secret);
      assertThat(refused.getResponse().getStatus()).isEqualTo(403);
      assertThat(refused.getResponse().getContentAsString())
          .contains("API_TOKEN_ADMIN_ONLY")
          .contains("réservés aux administrateurs");
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM audit_entry WHERE action = 'API_TOKEN_REFUSED'"
                      + " AND username = ?",
                  Integer.class,
                  user.getUsername()))
          .isEqualTo(1);
      // Le jeton reste en base, non révoqué : la décision est réversible.
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM api_token t JOIN app_user u ON u.id = t.user_id"
                      + " WHERE u.username = ? AND t.revoked_at IS NULL",
                  Integer.class,
                  user.getUsername()))
          .isEqualTo(1);
      // Ni l'opérateur ni le lecteur ne gèrent plus de jetons.
      mockMvc
          .perform(get("/api/v1/jetons").header("Authorization", "Bearer " + jwt.issue(user)))
          .andExpect(status().isForbidden());
    }
  }

  @Test
  void unJetonRevoqueNEstPlusAccepte() throws Exception {
    JsonNode created = createToken("À révoquer", null);
    String secret = created.get("secret").asText();
    String id = created.get("token").get("id").asText();

    assertThat(call(get("/api/v1/alertes"), secret).getResponse().getStatus()).isEqualTo(200);

    mockMvc
        .perform(delete("/api/v1/jetons/" + id).header("Authorization", "Bearer " + ownerJwt))
        .andExpect(status().isNoContent());
    em.flush();
    em.clear();

    assertThat(call(get("/api/v1/alertes"), secret).getResponse().getStatus()).isEqualTo(401);
  }

  @Test
  void unJetonExpireNEstPlusAccepte() throws Exception {
    JsonNode expired = createToken("Expiré", 30);
    String expiredSecret = expired.get("secret").asText();
    String liveSecret = createToken("Encore valide", null).get("secret").asText();
    // L'ordre compte : écrire les changements JPA en attente, puis la date en SQL, puis vider le
    // cache pour que le service relise la vraie ligne.
    em.flush();
    jdbc.update(
        "UPDATE api_token SET expires_at = now() - interval '1 day' WHERE id = ?",
        UUID.fromString(expired.get("token").get("id").asText()));
    em.clear();

    assertThat(call(get("/api/v1/alertes"), expiredSecret).getResponse().getStatus())
        .isEqualTo(401);
    assertThat(call(get("/api/v1/alertes"), liveSecret).getResponse().getStatus()).isEqualTo(200);
  }

  @Test
  void desactiverUnCompteCoupeTousSesJetons() throws Exception {
    String secret = createToken("Encore valide", null).get("secret").asText();
    assertThat(call(get("/api/v1/alertes"), secret).getResponse().getStatus()).isEqualTo(200);

    em.flush();
    jdbc.update("UPDATE app_user SET active = false WHERE id = ?", owner.getId());
    em.clear();

    assertThat(call(get("/api/v1/alertes"), secret).getResponse().getStatus()).isEqualTo(401);
  }

  @Test
  void onNeVoitNiNeRevoqueLesJetonsDUnAutreCompte() throws Exception {
    JsonNode mine = createToken("Le mien", null);
    AppUser other = account("jeton-autre", Role.ADMIN);
    String otherJwt = jwt.issue(other);

    mockMvc
        .perform(get("/api/v1/jetons").header("Authorization", "Bearer " + otherJwt))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isEmpty());

    mockMvc
        .perform(
            delete("/api/v1/jetons/" + mine.get("token").get("id").asText())
                .header("Authorization", "Bearer " + otherJwt))
        .andExpect(status().isNotFound());
  }

  @Test
  void deuxJetonsNePeuventPasPorterLeMemeNom() throws Exception {
    createToken("Doublon", null);

    mockMvc
        .perform(
            post("/api/v1/jetons")
                .header("Authorization", "Bearer " + ownerJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Doublon\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value("Vous avez déjà un jeton de ce nom"));
  }

  @Test
  void unJetonInventeNOuvreRien() throws Exception {
    assertThat(call(get("/api/v1/alertes"), "tdic_inconnu").getResponse().getStatus())
        .isEqualTo(401);
  }
}

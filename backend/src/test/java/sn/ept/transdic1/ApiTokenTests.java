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
  @Autowired private JdbcTemplate jdbc;
  @Autowired private jakarta.persistence.EntityManager em;

  private AppUser operator;
  private String operatorJwt;

  @BeforeEach
  void data() {
    operator = account("jeton-noc", Role.NOC_OPERATOR);
    operatorJwt = jwt.issue(operator);
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
                    .header("Authorization", "Bearer " + operatorJwt)
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
        .perform(get("/api/v1/jetons").header("Authorization", "Bearer " + operatorJwt))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].name").value("Tableau Grafana"))
        .andExpect(jsonPath("$[0].secret").doesNotExist());

    // Le jeton remplace un jeton de connexion sur une requête ordinaire.
    assertThat(call(get("/api/v1/alertes"), secret).getResponse().getStatus()).isEqualTo(200);
  }

  @Test
  void unJetonNAPasPlusDeDroitsQueSonProprietaire() throws Exception {
    String secret = createToken("Lecture seule", null).get("secret").asText();

    // Un opérateur NOC ne gère pas les comptes : son jeton non plus.
    assertThat(call(get("/api/v1/utilisateurs"), secret).getResponse().getStatus()).isEqualTo(403);
  }

  @Test
  void unJetonRevoqueNEstPlusAccepte() throws Exception {
    JsonNode created = createToken("À révoquer", null);
    String secret = created.get("secret").asText();
    String id = created.get("token").get("id").asText();

    assertThat(call(get("/api/v1/alertes"), secret).getResponse().getStatus()).isEqualTo(200);

    mockMvc
        .perform(delete("/api/v1/jetons/" + id).header("Authorization", "Bearer " + operatorJwt))
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
    jdbc.update("UPDATE app_user SET active = false WHERE id = ?", operator.getId());
    em.clear();

    assertThat(call(get("/api/v1/alertes"), secret).getResponse().getStatus()).isEqualTo(401);
  }

  @Test
  void onNeVoitNiNeRevoqueLesJetonsDUnAutreCompte() throws Exception {
    JsonNode mine = createToken("Le mien", null);
    AppUser other = account("jeton-autre", Role.NOC_OPERATOR);
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
                .header("Authorization", "Bearer " + operatorJwt)
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

package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.security.domain.AppUser;
import sn.ept.transdic1.security.domain.Role;
import sn.ept.transdic1.security.repository.UserRepository;
import sn.ept.transdic1.security.service.JwtService;
import sn.ept.transdic1.security.service.Totp;

/**
 * Double authentification de bout en bout : préparation, activation par un premier code, connexion
 * qui réclame puis vérifie le code, coupure par le propriétaire et par un administrateur.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class TwoFactorTests {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String PASSWORD = "MotDePasse-123456";

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private PasswordEncoder encoder;
  @Autowired private JwtService jwt;
  @Autowired private jakarta.persistence.EntityManager em;

  private AppUser operator;
  private String operatorJwt;

  @BeforeEach
  void data() {
    operator = account("2fa-noc", Role.NOC_OPERATOR);
    operatorJwt = jwt.issue(operator);
  }

  private AppUser account(String prefix, Role role) {
    String unique = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    return users.saveAndFlush(
        new AppUser(unique, unique + "@example.test", encoder.encode(PASSWORD), role));
  }

  private String prepare() throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/auth/double-authentification/preparer")
                    .header("Authorization", "Bearer " + operatorJwt))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
    assertThat(body.get("uri").asText()).startsWith("otpauth://totp/Trans-DIC1:");
    return body.get("secret").asText();
  }

  private void activate(String secret) throws Exception {
    mockMvc
        .perform(
            post("/api/v1/auth/double-authentification/activer")
                .header("Authorization", "Bearer " + operatorJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + Totp.code(secret, Instant.now()) + "\"}"))
        .andExpect(status().isNoContent());
    em.flush();
    em.clear();
  }

  private MvcResult login(String code) throws Exception {
    String body =
        code == null
            ? "{\"username\":\"" + operator.getUsername() + "\",\"password\":\"" + PASSWORD + "\"}"
            : "{\"username\":\""
                + operator.getUsername()
                + "\",\"password\":\""
                + PASSWORD
                + "\",\"code\":\""
                + code
                + "\"}";
    return mockMvc
        .perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
        .andReturn();
  }

  @Test
  void unSecretPrepareMaisPasConfirmeNeChangeRienALaConnexion() throws Exception {
    prepare();
    em.flush();
    em.clear();

    assertThat(login(null).getResponse().getStatus()).isEqualTo(200);
    mockMvc
        .perform(
            get("/api/v1/auth/double-authentification")
                .header("Authorization", "Bearer " + operatorJwt))
        .andExpect(jsonPath("$.enabled").value(false))
        .andExpect(jsonPath("$.prepared").value(true));
  }

  @Test
  void uneFoisActiveeLaConnexionReclamePuisVerifieLeCode() throws Exception {
    String secret = prepare();
    activate(secret);

    // Sans code : refusée, avec un message qui dit quoi faire.
    MvcResult missing = login(null);
    assertThat(missing.getResponse().getStatus()).isEqualTo(401);
    assertThat(missing.getResponse().getContentAsString())
        .contains("Code de double authentification requis");

    // Mauvais code : refusée, sans en dire plus.
    assertThat(login("000000").getResponse().getStatus()).isEqualTo(401);

    // Bon code : connexion ouverte.
    assertThat(login(Totp.code(secret, Instant.now())).getResponse().getStatus()).isEqualTo(200);
  }

  @Test
  void unCodeFauxALActivationNActivePas() throws Exception {
    prepare();
    em.flush();
    em.clear();

    mockMvc
        .perform(
            post("/api/v1/auth/double-authentification/activer")
                .header("Authorization", "Bearer " + operatorJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"000000\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("Code incorrect"));

    assertThat(login(null).getResponse().getStatus()).isEqualTo(200);
  }

  @Test
  void onNePeutPasActiverSansAvoirPrepare() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/auth/double-authentification/activer")
                .header("Authorization", "Bearer " + operatorJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"123456\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("Commencez par préparer la double authentification"));
  }

  @Test
  void couperExigeLeMotDePasse() throws Exception {
    String secret = prepare();
    activate(secret);

    mockMvc
        .perform(
            delete("/api/v1/auth/double-authentification")
                .header("Authorization", "Bearer " + operatorJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"mauvais-mot-de-passe\"}"))
        .andExpect(status().isBadRequest());
    em.flush();
    em.clear();
    assertThat(login(null).getResponse().getStatus()).isEqualTo(401);

    mockMvc
        .perform(
            delete("/api/v1/auth/double-authentification")
                .header("Authorization", "Bearer " + operatorJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"" + PASSWORD + "\"}"))
        .andExpect(status().isNoContent());
    em.flush();
    em.clear();
    assertThat(login(null).getResponse().getStatus()).isEqualTo(200);
  }

  @Test
  void unAdministrateurPeutLaCouperPourUnTelephonePerdu() throws Exception {
    String secret = prepare();
    activate(secret);
    AppUser boss = account("2fa-admin", Role.ADMIN);

    // Un opérateur ne coupe pas celle des autres.
    mockMvc
        .perform(
            delete("/api/v1/auth/double-authentification/comptes/" + operator.getId())
                .header("Authorization", "Bearer " + operatorJwt))
        .andExpect(status().isForbidden());

    mockMvc
        .perform(
            delete("/api/v1/auth/double-authentification/comptes/" + operator.getId())
                .header("Authorization", "Bearer " + jwt.issue(boss)))
        .andExpect(status().isNoContent());
    em.flush();
    em.clear();

    assertThat(login(null).getResponse().getStatus()).isEqualTo(200);
  }
}

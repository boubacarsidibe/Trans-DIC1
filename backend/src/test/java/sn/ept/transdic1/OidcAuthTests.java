package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.security.domain.AppUser;
import sn.ept.transdic1.security.domain.Role;
import sn.ept.transdic1.security.repository.UserRepository;

/**
 * Connexion unique OIDC : jetons d'un fournisseur de type Keycloak (décodeur de test à la place du
 * vrai fournisseur), conversion des rôles, création du compte local, connexion locale préservée.
 */
@SpringBootTest(
    properties = {"auth.oidc.enabled=true", "auth.oidc.issuer-uri=https://sso.test/realms/ept"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class OidcAuthTests {
  private static final String SUFFIX = UUID.randomUUID().toString().substring(0, 8);
  private static volatile String nocRole = "trans-dic1-noc";

  @TestConfiguration
  static class FakeProvider {
    /** Faux fournisseur : chaque jeton de test correspond à un utilisateur et ses rôles. */
    @Bean
    @org.springframework.context.annotation.Primary
    JwtDecoder testJwtDecoder() {
      return token -> {
        Map<String, Object> claims =
            switch (token) {
              case "jeton-noc" ->
                  Map.of(
                      "preferred_username", "sso-noc-" + SUFFIX,
                      "email", "noc-" + SUFFIX + "@ept.sn",
                      "realm_access", Map.of("roles", List.of("offline_access", nocRole)));
              case "jeton-admin" ->
                  Map.of(
                      "preferred_username",
                      "sso-adm-" + SUFFIX,
                      "resource_access",
                      Map.of("trans-dic1-web", Map.of("roles", List.of("trans-dic1-admin"))));
              case "jeton-sans-role" ->
                  Map.of(
                      "preferred_username",
                      "sso-x-" + SUFFIX,
                      "realm_access",
                      Map.of("roles", List.of("offline_access")));
              default -> throw new BadJwtException("jeton inconnu");
            };
        return Jwt.withTokenValue(token)
            .header("alg", "RS256")
            .issuer("https://sso.test/realms/ept")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(300))
            .claims(c -> c.putAll(claims))
            .build();
      };
    }
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private PasswordEncoder encoder;

  @Test
  void unJetonDuFournisseurDonneLeRoleEtCreeLeCompteLocal() throws Exception {
    mockMvc
        .perform(get("/api/v1/auth/moi").header("Authorization", "Bearer jeton-noc"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.username").value("sso-noc-" + SUFFIX))
        .andExpect(jsonPath("$.role").value("NOC_OPERATOR"));
    mockMvc
        .perform(get("/api/v1/alertes").header("Authorization", "Bearer jeton-noc"))
        .andExpect(status().isOk());
    mockMvc
        .perform(get("/api/v1/utilisateurs").header("Authorization", "Bearer jeton-noc"))
        .andExpect(status().isForbidden());

    AppUser created = users.findByUsername("sso-noc-" + SUFFIX).orElseThrow();
    assertThat(created.getRole()).isEqualTo(Role.NOC_OPERATOR);
    assertThat(created.getEmail()).isEqualTo("noc-" + SUFFIX + "@ept.sn");
  }

  @Test
  void lesRolesDuClientSontAussiLusEtLAdministrateurAdministre() throws Exception {
    mockMvc
        .perform(get("/api/v1/utilisateurs").header("Authorization", "Bearer jeton-admin"))
        .andExpect(status().isOk());
  }

  @Test
  void leRoleSuitLeFournisseur() throws Exception {
    mockMvc
        .perform(get("/api/v1/auth/moi").header("Authorization", "Bearer jeton-noc"))
        .andExpect(status().isOk());
    nocRole = "trans-dic1-viewer";
    try {
      mockMvc
          .perform(get("/api/v1/auth/moi").header("Authorization", "Bearer jeton-noc"))
          .andExpect(jsonPath("$.role").value("VIEWER"));
      assertThat(users.findByUsername("sso-noc-" + SUFFIX).orElseThrow().getRole())
          .isEqualTo(Role.VIEWER);
    } finally {
      nocRole = "trans-dic1-noc";
    }
  }

  @Test
  void unJetonSansRoleOuInvalideEstRefuse() throws Exception {
    mockMvc
        .perform(get("/api/v1/alertes").header("Authorization", "Bearer jeton-sans-role"))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(get("/api/v1/alertes").header("Authorization", "Bearer n-importe-quoi"))
        .andExpect(status().isUnauthorized());
    assertThat(users.findByUsername("sso-x-" + SUFFIX)).isEmpty();
  }

  @Test
  void laConnexionLocaleResteUtilisableEtLaConfigurationEstPublique() throws Exception {
    users.save(
        new AppUser(
            "loc-" + SUFFIX,
            "loc-" + SUFFIX + "@example.test",
            encoder.encode("Mot-de-passe-test-123"),
            Role.VIEWER));
    var body =
        mockMvc
            .perform(
                post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"username\":\"loc-"
                            + SUFFIX
                            + "\",\"password\":\"Mot-de-passe-test-123\"}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String local = JsonPath.read(body, "$.accessToken");
    mockMvc
        .perform(get("/api/v1/auth/moi").header("Authorization", "Bearer " + local))
        .andExpect(jsonPath("$.role").value("VIEWER"));

    mockMvc
        .perform(get("/api/v1/auth/configuration"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.localLogin").value(true))
        .andExpect(jsonPath("$.oidc.enabled").value(true))
        .andExpect(jsonPath("$.oidc.issuer").value("https://sso.test/realms/ept"))
        .andExpect(jsonPath("$.oidc.clientId").value("trans-dic1-web"));
  }
}

package sn.ept.transdic1.statuspage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static sn.ept.transdic1.statuspage.StatusPageTestSupport.from;
import static sn.ept.transdic1.statuspage.StatusPageTestSupport.randomAddress;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Régression de sécurité (ADR 0026) : l'ouverture sans connexion de la page de statut publique
 * n'ouvre rien d'autre. Chaque opération du contrat OpenAPI réel, hormis la connexion et la lecture
 * publique, répond 401 sans jeton.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StatusPageAuthRegressionTests {
  /** Seules opérations sans connexion de l'API (méthode et chemin du contrat). */
  private static final Set<String> PUBLIC_OPERATIONS =
      Set.of(
          "POST /api/v1/auth/login",
          "POST /api/v1/auth/refresh",
          "POST /api/v1/auth/logout",
          "GET /api/v1/auth/configuration",
          "GET /api/v1/public/statut/{slug}");

  private static final Set<String> METHODS = Set.of("get", "post", "put", "delete", "patch");

  @Autowired private MockMvc mockMvc;

  @Test
  void toutCheminDeLApiHorsListeBlancheExigeUneAuthentification() throws Exception {
    String json =
        mockMvc
            .perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    JsonNode paths = new ObjectMapper().readTree(json).get("paths");
    var checked = new ArrayList<String>();
    var publicSeen = new ArrayList<String>();
    var open = new ArrayList<String>();
    for (var path : (Iterable<java.util.Map.Entry<String, JsonNode>>) paths::fields) {
      for (var operation :
          (Iterable<java.util.Map.Entry<String, JsonNode>>) path.getValue()::fields) {
        if (!METHODS.contains(operation.getKey())) {
          continue;
        }
        String method = operation.getKey().toUpperCase(Locale.ROOT);
        String key = method + " " + path.getKey();
        if (PUBLIC_OPERATIONS.contains(key)) {
          publicSeen.add(key);
          continue;
        }
        String uri = path.getKey().replaceAll("\\{[^}]+}", UUID.randomUUID().toString());
        int code =
            mockMvc
                .perform(
                    request(HttpMethod.valueOf(method), uri)
                        .with(from(randomAddress()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn()
                .getResponse()
                .getStatus();
        checked.add(key);
        if (code != 401) {
          open.add(key + " -> " + code);
        }
      }
    }
    assertThat(open).as("opérations accessibles sans connexion").isEmpty();
    // Le test n'est pas vide : tout le contrat est parcouru, et la lecture publique est déclarée.
    assertThat(checked).hasSizeGreaterThan(100);
    assertThat(publicSeen).contains("GET /api/v1/public/statut/{slug}");
  }

  @Test
  void laLectureAnonymeNeVautQuePourLaLecture() throws Exception {
    for (HttpMethod method :
        List.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE, HttpMethod.PATCH)) {
      mockMvc
          .perform(
              request(method, "/api/v1/public/statut/services-ept")
                  .with(from(randomAddress()))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{}"))
          .andExpect(status().isUnauthorized());
    }
  }
}

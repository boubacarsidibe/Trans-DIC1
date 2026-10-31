package sn.ept.transdic1;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Connexion locale fermée par l'exploitation : seul le fournisseur d'identité ouvre une session.
 */
@SpringBootTest(properties = "auth.local.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OidcOnlyLoginTests {
  @Autowired private MockMvc mockMvc;

  @Test
  void laConnexionLocaleEstRefuseeEtAnnonceeCommeFermee() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"Mot-de-passe-test-123\"}"))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(get("/api/v1/auth/configuration"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.localLogin").value(false));
  }
}

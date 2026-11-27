package sn.ept.transdic1;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Fermeture d'un flux SSE : le conteneur rejoue la requête en mode ASYNC pour la terminer, sans le
 * jeton (le filtre JWT ne passe qu'une fois par requête). Ce rejeu ne doit pas être refusé — il
 * l'était, ce qui écrivait un « Access Denied » en ERROR à chaque onglet fermé, constaté pendant le
 * test de haute disponibilité. La requête d'origine, elle, exige toujours un jeton.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityAsyncDispatchTests {
  @Autowired private MockMvc mockMvc;

  @Test
  @DisplayName("Ouvrir le flux sans jeton reste refusé")
  void openingTheStreamWithoutATokenIsStillRefused() throws Exception {
    mockMvc
        .perform(get("/api/v1/flux").accept(MediaType.TEXT_EVENT_STREAM))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("Le rejeu asynchrone qui termine un flux n'est pas refusé par la chaîne de sécurité")
  void theAsyncRedispatchThatEndsAStreamIsNotRefused() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/flux")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .with(
                    r -> {
                      r.setDispatcherType(DispatcherType.ASYNC);
                      return r;
                    }))
        .andExpect(request().asyncStarted());
  }
}

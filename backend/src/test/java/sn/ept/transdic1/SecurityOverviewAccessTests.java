package sn.ept.transdic1;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** Écran Sécurité : réservé à l'administrateur (ADR 0058). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityOverviewAccessTests {
  @Autowired private MockMvc mockMvc;

  @Test
  @WithMockUser(roles = "ADMIN")
  void lAdministrateurLitLaVueSecurite() throws Exception {
    mockMvc.perform(get("/api/v1/securite")).andExpect(status().isOk());
  }

  @Test
  @WithMockUser(roles = "NOC_OPERATOR")
  void lOperateurNeLitPasLaVueSecurite() throws Exception {
    mockMvc.perform(get("/api/v1/securite")).andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(roles = "VIEWER")
  void leLecteurNeLitPasLaVueSecurite() throws Exception {
    mockMvc.perform(get("/api/v1/securite")).andExpect(status().isForbidden());
  }
}

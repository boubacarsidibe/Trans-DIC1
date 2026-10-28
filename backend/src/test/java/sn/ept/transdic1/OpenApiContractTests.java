package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Le contrat OpenAPI versionné (docs/api/openapi.yaml, d'où sont générés les types du frontend)
 * doit correspondre à l'API réelle. Pour le régénérer après une modification de l'API : {@code
 * ./mvnw test -Dtest=OpenApiContractTests -Dopenapi.update=true}, puis {@code npm run api:types}
 * dans frontend.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenApiContractTests {
  private static final Path CONTRACT = Path.of("..", "docs", "api", "openapi.yaml");

  @Autowired private MockMvc mockMvc;

  @Test
  void theVersionedContractMatchesTheRunningApi() throws Exception {
    String generated =
        mockMvc
            .perform(get("/v3/api-docs.yaml"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8)
            .replace("\r\n", "\n");

    if (Boolean.getBoolean("openapi.update")) {
      Files.writeString(CONTRACT, generated, StandardCharsets.UTF_8);
      return;
    }
    String committed = Files.readString(CONTRACT, StandardCharsets.UTF_8).replace("\r\n", "\n");
    assertThat(generated)
        .as(
            "docs/api/openapi.yaml est périmé : relancer avec -Dopenapi.update=true puis"
                + " npm run api:types")
        .isEqualTo(committed);
  }
}

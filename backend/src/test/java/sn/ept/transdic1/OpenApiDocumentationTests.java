package sn.ept.transdic1;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

class OpenApiDocumentationTests {

  @Nested
  @SpringBootTest
  @AutoConfigureMockMvc
  @ActiveProfiles("test")
  class Enabled {
    @Autowired private MockMvc mockMvc;

    @Test
    void contractIsPublicAndDescribesBearerSecurity() throws Exception {
      mockMvc
          .perform(get("/v3/api-docs"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.info.title").value("API Trans-DIC1"))
          .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
          .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.bearerFormat").value("JWT"))
          .andExpect(jsonPath("$.security[*].bearerAuth").exists());
    }

    @Test
    void yamlContractIsPublic() throws Exception {
      mockMvc.perform(get("/v3/api-docs.yaml")).andExpect(status().isOk());
    }

    @Test
    void contractListsBusinessEndpoints() throws Exception {
      mockMvc
          .perform(get("/v3/api-docs"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.paths", hasKey("/api/v1/equipements")))
          .andExpect(jsonPath("$.paths", hasKey("/api/v1/equipements/{id}")))
          .andExpect(jsonPath("$.paths", hasKey("/api/v1/metrics/latest")))
          .andExpect(jsonPath("$.paths", hasKey("/api/v1/auth/login")))
          .andExpect(jsonPath("$.tags[*].name", hasItem("Équipements")));
    }

    @Test
    void sharedErrorFormatAndProtectedResponsesAreDocumented() throws Exception {
      mockMvc
          .perform(get("/v3/api-docs"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.components.schemas", hasKey("ApiError")))
          .andExpect(jsonPath("$.paths['/api/v1/equipements'].get.responses", hasKey("401")))
          .andExpect(jsonPath("$.paths['/api/v1/equipements'].get.responses", hasKey("403")));
    }

    @Test
    void loginIsDocumentedWithoutAuthentication() throws Exception {
      mockMvc
          .perform(get("/v3/api-docs"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.paths['/api/v1/auth/login'].post.security").isEmpty())
          .andExpect(jsonPath("$.paths['/api/v1/auth/login'].post.responses", hasKey("401")));
    }
  }

  @Nested
  @SpringBootTest(properties = {"springdoc.api-docs.enabled=false", "springdoc.swagger-ui.enabled=false"})
  @AutoConfigureMockMvc
  @ActiveProfiles("test")
  class Disabled {
    @Autowired private MockMvc mockMvc;

    @Test
    void contractIsNotExposedWhenDisabled() throws Exception {
      mockMvc.perform(get("/v3/api-docs")).andExpect(status().isNotFound());
    }
  }
}

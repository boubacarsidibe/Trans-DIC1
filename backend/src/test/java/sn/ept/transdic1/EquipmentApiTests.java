package sn.ept.transdic1;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class EquipmentApiTests {
  private static final String SITE_ID = "10000000-0000-0000-0000-000000000001";

  @Autowired private MockMvc mockMvc;

  private static String body(String code, String name, String address) {
    return """
        {"inventoryCode":"%s","name":"%s","managementAddress":"%s",
         "type":"SERVER","status":"UP","siteId":"%s"}
        """
        .formatted(code, name, address, SITE_ID);
  }

  private String create(String code, String address) throws Exception {
    return mockMvc
        .perform(
            post("/api/v1/equipements")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(code, "Equipement test", address)))
        .andExpect(status().isCreated())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void creerUnEquipement() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/equipements")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("TEST-API-001", "Equipement test", "192.0.2.100")))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.inventoryCode").value("TEST-API-001"));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void creationInvalideRetourne400AvecLesChampsEnErreur() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/equipements")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("TEST-API-002", "", "192.0.2.101")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.fields.name").exists());
  }

  @Test
  @WithMockUser(roles = "VIEWER")
  void equipementInconnuRetourne404() throws Exception {
    mockMvc
        .perform(get("/api/v1/equipements/" + UUID.randomUUID()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value(404));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void adresseDejaUtiliseeRetourne409() throws Exception {
    create("TEST-API-003", "192.0.2.103");
    mockMvc
        .perform(
            post("/api/v1/equipements")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("TEST-API-004", "Autre", "192.0.2.103")))
        .andExpect(status().isConflict());
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void modificationAvecAdresseDejaUtiliseeRetourne409() throws Exception {
    create("TEST-API-005", "192.0.2.105");
    String second = create("TEST-API-006", "192.0.2.106");
    String id = JsonPath.read(second, "$.id");
    mockMvc
        .perform(
            put("/api/v1/equipements/" + id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("TEST-API-006", "Equipement test", "192.0.2.105")))
        .andExpect(status().isConflict());
  }

  @Test
  @WithMockUser(roles = "VIEWER")
  void listePagineeEtFiltrableParType() throws Exception {
    mockMvc
        .perform(get("/api/v1/equipements?type=ROUTER"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content").isArray())
        .andExpect(jsonPath("$.content[0].type").value("ROUTER"))
        .andExpect(jsonPath("$.page.size").exists());
  }

  @Test
  @WithMockUser(roles = "NOC_OPERATOR")
  void unOperateurNocPeutCreerUnEquipement() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/equipements")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("TEST-API-007", "Equipement NOC", "192.0.2.107")))
        .andExpect(status().isCreated());
  }

  @Test
  @WithMockUser(roles = "VIEWER")
  void unLecteurNePeutPasCreerUnEquipement() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/equipements")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("TEST-API-008", "Equipement lecteur", "192.0.2.108")))
        .andExpect(status().isForbidden());
  }
}

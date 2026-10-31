package sn.ept.transdic1;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
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
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.security.domain.AppUser;
import sn.ept.transdic1.security.domain.Role;
import sn.ept.transdic1.security.repository.UserRepository;

/** Vues personnelles : création, ordre des widgets, validation, périmètre par utilisateur. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DashboardViewTests {
  private static final String PASSWORD = "Mot-de-passe-test-123";

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private PasswordEncoder encoder;

  private String alice;
  private String bob;

  @BeforeEach
  void data() throws Exception {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    users.save(
        new AppUser(
            "ali-" + suffix,
            "ali-" + suffix + "@example.test",
            encoder.encode(PASSWORD),
            Role.VIEWER));
    users.save(
        new AppUser(
            "bob-" + suffix,
            "bob-" + suffix + "@example.test",
            encoder.encode(PASSWORD),
            Role.NOC_OPERATOR));
    alice = login("ali-" + suffix);
    bob = login("bob-" + suffix);
  }

  private String login(String username) throws Exception {
    var body =
        mockMvc
            .perform(
                post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(body, "$.accessToken");
  }

  private String create(String jwt, String json) throws Exception {
    var body =
        mockMvc
            .perform(
                post("/api/v1/vues")
                    .header("Authorization", "Bearer " + jwt)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(body, "$.id");
  }

  @Test
  void unUtilisateurComposeEtOrdonneSesVues() throws Exception {
    create(
        alice, "{\"name\":\"Salle NOC\",\"widgets\":[\"summary\",\"open-alerts\",\"statuses\"]}");
    String id = create(alice, "{\"name\":\"Réseau\",\"widgets\":[\"down-equipments\"]}");

    mockMvc
        .perform(get("/api/v1/vues").header("Authorization", "Bearer " + alice))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].name").value("Salle NOC"))
        .andExpect(jsonPath("$[0].widgets[1]").value("open-alerts"))
        .andExpect(jsonPath("$[1].position").value(1));

    mockMvc
        .perform(
            put("/api/v1/vues/" + id)
                .header("Authorization", "Bearer " + alice)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Réseau\",\"widgets\":[\"severities\",\"down-equipments\"]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.widgets[0]").value("severities"));
  }

  @Test
  void lesVuesRestentDansLePerimetreDeLeurProprietaire() throws Exception {
    String id = create(alice, "{\"name\":\"Privée\",\"widgets\":[\"summary\"]}");

    mockMvc
        .perform(get("/api/v1/vues").header("Authorization", "Bearer " + bob))
        .andExpect(jsonPath("$.length()").value(0));
    mockMvc
        .perform(
            put("/api/v1/vues/" + id)
                .header("Authorization", "Bearer " + bob)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Volée\",\"widgets\":[\"summary\"]}"))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(delete("/api/v1/vues/" + id).header("Authorization", "Bearer " + bob))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(delete("/api/v1/vues/" + id).header("Authorization", "Bearer " + alice))
        .andExpect(status().isNoContent());
  }

  @Test
  void lesWidgetsInconnusDoublonsEtNomsRepetesSontRefuses() throws Exception {
    var bad =
        new String[] {
          "{\"name\":\"X\",\"widgets\":[\"inconnu\"]}",
          "{\"name\":\"X\",\"widgets\":[\"summary\",\"summary\"]}",
          "{\"name\":\"X\",\"widgets\":[]}",
          "{\"name\":\"\",\"widgets\":[\"summary\"]}"
        };
    for (String json : bad) {
      mockMvc
          .perform(
              post("/api/v1/vues")
                  .header("Authorization", "Bearer " + alice)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(json))
          .andExpect(status().isBadRequest());
    }
    create(alice, "{\"name\":\"Unique\",\"widgets\":[\"summary\"]}");
    mockMvc
        .perform(
            post("/api/v1/vues")
                .header("Authorization", "Bearer " + alice)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Unique\",\"widgets\":[\"statuses\"]}"))
        .andExpect(status().isConflict());
    mockMvc.perform(get("/api/v1/vues")).andExpect(status().isUnauthorized());
  }
}

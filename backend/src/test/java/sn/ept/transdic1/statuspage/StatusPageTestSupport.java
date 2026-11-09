package sn.ept.transdic1.statuspage;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.EquipmentStatus;
import sn.ept.transdic1.inventory.domain.EquipmentType;
import sn.ept.transdic1.inventory.domain.MonitoredService;
import sn.ept.transdic1.inventory.domain.ServiceType;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.MonitoredServiceRepository;
import sn.ept.transdic1.security.domain.AppUser;
import sn.ept.transdic1.security.domain.Role;
import sn.ept.transdic1.security.repository.UserRepository;

/** Données et appels communs aux tests des pages de statut. */
final class StatusPageTestSupport {
  static final String PASSWORD = "Mot-de-passe-test-123";
  static final String BASE = "/api/v1/pages-statut";
  static final String PUBLIC = "/api/v1/public/statut/";

  private StatusPageTestSupport() {}

  static String suffix() {
    return UUID.randomUUID().toString().substring(0, 8);
  }

  /** Adresse cliente propre à un test : la limite par adresse d'un test ne gêne pas les autres. */
  static RequestPostProcessor from(String address) {
    return request -> {
      request.setRemoteAddr(address);
      return request;
    };
  }

  static String randomAddress() {
    var r = ThreadLocalRandom.current();
    return "198.18." + r.nextInt(0, 255) + "." + r.nextInt(1, 254);
  }

  static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, String token) {
    return request.header("Authorization", "Bearer " + token);
  }

  static AppUser user(UserRepository users, PasswordEncoder encoder, String name, Role role) {
    return users.saveAndFlush(
        new AppUser(name, name + "@example.test", encoder.encode(PASSWORD), role));
  }

  static String login(MockMvc mockMvc, AppUser user) throws Exception {
    var body =
        mockMvc
            .perform(
                post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"username\":\""
                            + user.getUsername()
                            + "\",\"password\":\""
                            + PASSWORD
                            + "\"}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(body, "$.accessToken");
  }

  static Equipment equipment(EquipmentRepository equipments, Site site, String name) {
    var r = ThreadLocalRandom.current();
    return equipments.saveAndFlush(
        new Equipment(
            "SP-" + UUID.randomUUID(),
            name,
            "10.251." + r.nextInt(1, 254) + "." + r.nextInt(1, 254),
            EquipmentType.SERVER,
            EquipmentStatus.UP,
            site));
  }

  static MonitoredService probe(
      MonitoredServiceRepository services, Equipment equipment, String name, ServiceType type) {
    return services.saveAndFlush(new MonitoredService(equipment, name, type));
  }

  static UUID createPage(MockMvc mockMvc, String token, String slug, String visibility)
      throws Exception {
    String body =
        mockMvc
            .perform(
                auth(post(BASE), token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"slug\":\""
                            + slug
                            + "\",\"title\":\"Services de l'école\",\"description\":\"État des"
                            + " services\",\"visibility\":\""
                            + visibility
                            + "\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return UUID.fromString(JsonPath.read(body, "$.id"));
  }

  static UUID addComponent(
      MockMvc mockMvc, String token, UUID page, String name, String type, UUID source)
      throws Exception {
    String body =
        mockMvc
            .perform(
                auth(post(BASE + "/" + page + "/composants"), token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"displayName\":\""
                            + name
                            + "\",\"groupName\":\"Services\",\"sourceType\":\""
                            + type
                            + "\",\"sourceId\":\""
                            + source
                            + "\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return UUID.fromString(JsonPath.read(body, "$.id"));
  }

  static UUID createIncident(MockMvc mockMvc, String token, UUID page, String title, UUID component)
      throws Exception {
    String components = component == null ? "[]" : "[\"" + component + "\"]";
    String body =
        mockMvc
            .perform(
                auth(post(BASE + "/" + page + "/incidents"), token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"title\":\""
                            + title
                            + "\",\"status\":\"INVESTIGATING\",\"impact\":\"MAJOR\","
                            + "\"message\":\"Les courriels arrivent en retard.\","
                            + "\"componentIds\":"
                            + components
                            + "}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return UUID.fromString(JsonPath.read(body, "$.id"));
  }
}

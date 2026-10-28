package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.EquipmentStatus;
import sn.ept.transdic1.inventory.domain.EquipmentType;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.SiteRepository;
import sn.ept.transdic1.security.domain.AppUser;
import sn.ept.transdic1.security.domain.Role;
import sn.ept.transdic1.security.repository.UserRepository;

/** Configuration de la collecte SNMP : droits, secrets par référence, validation, mises à jour. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SnmpConfigApiTests {
  private static final String PASSWORD = "Mot-de-passe-test-123";

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private PasswordEncoder encoder;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EntityManager em;

  private String admin;
  private String noc;
  private String url;

  @BeforeEach
  void data() throws Exception {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    users.save(
        new AppUser(
            "adm-" + suffix,
            "adm-" + suffix + "@example.test",
            encoder.encode(PASSWORD),
            Role.ADMIN));
    users.save(
        new AppUser(
            "noc-" + suffix,
            "noc-" + suffix + "@example.test",
            encoder.encode(PASSWORD),
            Role.NOC_OPERATOR));
    admin = login("adm-" + suffix);
    noc = login("noc-" + suffix);
    String unique = UUID.randomUUID().toString();
    Site site = sites.save(new Site("CFG-" + suffix, "Site de configuration"));
    Equipment equipment =
        equipments.save(
            new Equipment(
                "CFG-EQ-" + unique,
                "Routeur configuré",
                "10.236."
                    + (Math.floorMod(unique.hashCode(), 250) + 1)
                    + "."
                    + (Math.floorMod(unique.substring(9).hashCode(), 250) + 1),
                EquipmentType.ROUTER,
                EquipmentStatus.UP,
                site));
    equipments.flush();
    url = "/api/v1/equipements/" + equipment.getId() + "/collecte-snmp";
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

  private ResultActions call(String method, String jwt, String body) throws Exception {
    var request =
        switch (method) {
          case "PUT" -> put(url);
          case "DELETE" -> delete(url);
          default -> get(url);
        };
    request = request.header("Authorization", "Bearer " + jwt);
    if (body != null) {
      request = request.contentType(MediaType.APPLICATION_JSON).content(body);
    }
    return mockMvc.perform(request);
  }

  private static String v2c(String community, String metrics) {
    return "{\"name\":\"SNMP principal\",\"snmpVersion\":\"V2C\",\"communityReference\":\""
        + community
        + "\",\"pollIntervalSeconds\":60,\"timeoutSeconds\":3,\"retryCount\":1,\"metrics\":"
        + metrics
        + "}";
  }

  private static final String CPU =
      "{\"key\":\"cpu.usage\",\"name\":\"Charge CPU\",\"oid\":\"1.3.6.1.4.1.9.2.1.58.0\",\"unit\":\"%\",\"valueType\":\"INTEGER\"}";
  private static final String MEMORY =
      "{\"key\":\"memory.usage\",\"name\":\"Mémoire\",\"oid\":\".1.3.6.1.4.1.9.2.1.8.0\",\"unit\":\"%\",\"valueType\":\"DECIMAL\"}";

  @Test
  void onlyAdministratorsConfigureTheCollection() throws Exception {
    call("PUT", noc, v2c("env:SNMP_X", "[" + CPU + "]")).andExpect(status().isForbidden());
    call("GET", noc, null).andExpect(status().isForbidden());
    call("DELETE", noc, null).andExpect(status().isForbidden());
    mockMvc.perform(get(url)).andExpect(status().isUnauthorized());
  }

  @Test
  void aV2cConfigurationIsStoredWithItsMetricsAndNeverTheSecret() throws Exception {
    call("PUT", admin, v2c("env:SNMP_CAMPUS", "[" + CPU + "," + MEMORY + "]"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.snmpVersion").value("V2C"))
        .andExpect(jsonPath("$.communityReference").value("env:SNMP_CAMPUS"))
        .andExpect(jsonPath("$.pollIntervalSeconds").value(60))
        .andExpect(jsonPath("$.active").value(true))
        .andExpect(jsonPath("$.nextRunAt").isNotEmpty())
        .andExpect(jsonPath("$.metrics.length()").value(2))
        .andExpect(
            jsonPath("$.metrics[?(@.key=='memory.usage')].oid").value("1.3.6.1.4.1.9.2.1.8.0"));

    call("GET", admin, null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.metrics.length()").value(2));
  }

  @Test
  void aV3ConfigurationNeedsAnIdentityAndASecretReference() throws Exception {
    String v3 =
        "{\"name\":\"SNMP v3\",\"snmpVersion\":\"V3\",\"securityIdentity\":\"supervision\","
            + "\"secretReference\":\"env:SNMP_V3_CORE\",\"pollIntervalSeconds\":120,\"timeoutSeconds\":5,"
            + "\"retryCount\":2,\"metrics\":["
            + CPU
            + "]}";

    call("PUT", admin, v3)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.snmpVersion").value("V3"))
        .andExpect(jsonPath("$.securityIdentity").value("supervision"))
        .andExpect(jsonPath("$.communityReference").doesNotExist());
  }

  @Test
  void secretsInClearAndInconsistentSettingsAreRejected() throws Exception {
    // Une communauté en clair serait stockée en base : on n'accepte que des références.
    call("PUT", admin, v2c("public", "[" + CPU + "]")).andExpect(status().isBadRequest());
    call("PUT", admin, v2c("env:bad name", "[" + CPU + "]")).andExpect(status().isBadRequest());
    // v3 sans identité ou sans secret
    call(
            "PUT",
            admin,
            "{\"name\":\"x\",\"snmpVersion\":\"V3\",\"secretReference\":\"env:X\",\"pollIntervalSeconds\":60,\"timeoutSeconds\":3,\"retryCount\":0,\"metrics\":["
                + CPU
                + "]}")
        .andExpect(status().isBadRequest());
    call(
            "PUT",
            admin,
            "{\"name\":\"x\",\"snmpVersion\":\"V3\",\"securityIdentity\":\"u\",\"secretReference\":\"mot-de-passe\",\"pollIntervalSeconds\":60,\"timeoutSeconds\":3,\"retryCount\":0,\"metrics\":["
                + CPU
                + "]}")
        .andExpect(status().isBadRequest());
    // mélange v2c / v3
    call(
            "PUT",
            admin,
            v2c("env:SNMP_X", "[" + CPU + "]")
                .replace("\"retryCount\"", "\"securityIdentity\":\"u\",\"retryCount\""))
        .andExpect(status().isBadRequest());
  }

  @Test
  void invalidPollingAndMetricsAreRejected() throws Exception {
    call(
            "PUT",
            admin,
            v2c("env:SNMP_X", "[" + CPU + "]")
                .replace("\"pollIntervalSeconds\":60", "\"pollIntervalSeconds\":5"))
        .andExpect(status().isBadRequest());
    call(
            "PUT",
            admin,
            v2c("env:SNMP_X", "[" + CPU + "]")
                .replace("\"timeoutSeconds\":3", "\"timeoutSeconds\":0"))
        .andExpect(status().isBadRequest());
    call("PUT", admin, v2c("env:SNMP_X", "[]")).andExpect(status().isBadRequest());
    call("PUT", admin, v2c("env:SNMP_X", "[" + CPU + "," + CPU + "]"))
        .andExpect(status().isBadRequest());
    call("PUT", admin, v2c("env:SNMP_X", "[" + CPU.replace("INTEGER", "TEXT") + "]"))
        .andExpect(status().isBadRequest());
    call(
            "PUT",
            admin,
            v2c("env:SNMP_X", "[" + CPU.replace("1.3.6.1.4.1.9.2.1.58.0", "pas-un-oid") + "]"))
        .andExpect(status().isBadRequest());
    call("PUT", admin, v2c("env:SNMP_X", "[" + CPU.replace("cpu.usage", "CPU Usage") + "]"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void updatingKeepsTheConfigurationAndDeactivatesRemovedMetrics() throws Exception {
    call("PUT", admin, v2c("env:SNMP_X", "[" + CPU + "," + MEMORY + "]"))
        .andExpect(status().isOk());

    call("PUT", admin, v2c("env:SNMP_Y", "[" + CPU + "]"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.communityReference").value("env:SNMP_Y"))
        .andExpect(jsonPath("$.metrics.length()").value(2))
        .andExpect(jsonPath("$.metrics[?(@.key=='cpu.usage')].active").value(true))
        .andExpect(jsonPath("$.metrics[?(@.key=='memory.usage')].active").value(false));
    em.flush();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM collection_configuration WHERE mode = 'SNMP'", Integer.class))
        .isGreaterThanOrEqualTo(1);
  }

  @Test
  void deletingStopsTheCollectionButKeepsTheHistory() throws Exception {
    call("GET", admin, null).andExpect(status().isNotFound());
    call("PUT", admin, v2c("env:SNMP_X", "[" + CPU + "]")).andExpect(status().isOk());

    call("DELETE", admin, null).andExpect(status().isNoContent());

    call("GET", admin, null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.active").value(false));
    call("PUT", admin, v2c("env:SNMP_X", "[" + CPU + "]"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.active").value(true));
  }

  @Test
  void anUnknownEquipmentIsNotFound() throws Exception {
    mockMvc
        .perform(
            put("/api/v1/equipements/" + UUID.randomUUID() + "/collecte-snmp")
                .header("Authorization", "Bearer " + admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(v2c("env:SNMP_X", "[" + CPU + "]")))
        .andExpect(status().isNotFound());
  }
}

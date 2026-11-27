package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import sn.ept.transdic1.access.domain.SiteAccess;
import sn.ept.transdic1.access.repository.SiteAccessRepository;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.EquipmentStatus;
import sn.ept.transdic1.inventory.domain.EquipmentType;
import sn.ept.transdic1.inventory.domain.MonitoredService;
import sn.ept.transdic1.inventory.domain.ServiceType;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.MonitoredServiceRepository;
import sn.ept.transdic1.inventory.repository.SiteRepository;
import sn.ept.transdic1.security.domain.AppUser;
import sn.ept.transdic1.security.domain.Role;
import sn.ept.transdic1.security.repository.UserRepository;

/**
 * Services découverts par l'agent et suivis en un clic (ADR 0056), de l'instantané de l'agent à la
 * liste qu'il relit, sans toucher à sa configuration. Transactionnel : rien ne reste en base.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ServiceDiscoveryApiTests {
  private static final String PASSWORD = "Mot-de-passe-test-123";

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private PasswordEncoder encoder;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private MonitoredServiceRepository services;
  @Autowired private SiteAccessRepository access;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private jakarta.persistence.EntityManager em;

  private Equipment vm;
  private String adminJwt;
  private String viewerJwt;
  private String limitedJwt;
  private String agentToken;

  @BeforeEach
  void data() throws Exception {
    users.deleteAll();
    users.save(
        new AppUser("sd-admin", "sd-admin@example.test", encoder.encode(PASSWORD), Role.ADMIN));
    users.save(
        new AppUser("sd-viewer", "sd-viewer@example.test", encoder.encode(PASSWORD), Role.VIEWER));
    var limited =
        users.save(
            new AppUser(
                "sd-noc", "sd-noc@example.test", encoder.encode(PASSWORD), Role.NOC_OPERATOR));
    String unique = UUID.randomUUID().toString().substring(0, 6);
    Site site = sites.save(new Site("SD-" + unique, "Salle serveurs"));
    Site other = sites.save(new Site("SE-" + unique, "Autre"));
    access.save(new SiteAccess(limited, other, Instant.now()));
    vm =
        equipments.saveAndFlush(
            new Equipment(
                "SD-VM-" + unique,
                "vm-web-01",
                "192.0.2.1" + unique.substring(0, 2).replaceAll("[^0-9]", "0"),
                EquipmentType.VIRTUAL_MACHINE,
                EquipmentStatus.UP,
                site));
    em.flush();
    adminJwt = login("sd-admin");
    viewerJwt = login("sd-viewer");
    limitedJwt = login("sd-noc");
    String body =
        mockMvc
            .perform(
                post("/api/v1/equipements/" + vm.getId() + "/agent")
                    .header("Authorization", "Bearer " + adminJwt))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    agentToken = JsonPath.read(body, "$.token");
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

  private void sendSnapshot(String services) throws Exception {
    mockMvc
        .perform(
            post("/api/v1/agent/snapshot")
                .header("Authorization", "Bearer " + agentToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"hostname\":\"vm-web-01\",\"os\":\"Linux\",\"osVersion\":\"6.1\",\"architecture\":\"x86_64\","
                        + "\"cpuCount\":2,\"memoryTotal\":4000000000,\"partial\":false,"
                        + "\"processes\":{\"byCpu\":[],\"byMemory\":[]},"
                        + "\"services\":"
                        + services
                        + ","
                        + "\"listeningPorts\":[{\"protocol\":\"tcp\",\"address\":\"0.0.0.0\",\"port\":443},"
                        + "{\"protocol\":\"tcp6\",\"address\":\"::\",\"port\":22},{\"protocol\":\"tcp\",\"address\":\"0.0.0.0\",\"port\":22}]}"))
        .andExpect(status().isNoContent());
  }

  private ResultActions discovered(String jwt) throws Exception {
    return mockMvc.perform(
        get("/api/v1/equipements/" + vm.getId() + "/services-decouverts")
            .header("Authorization", "Bearer " + jwt));
  }

  private ResultActions follow(String name, String jwt) throws Exception {
    return mockMvc.perform(
        post("/api/v1/equipements/" + vm.getId() + "/services-suivis")
            .header("Authorization", "Bearer " + jwt)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"nom\":\"" + name + "\"}"));
  }

  private List<String> followedByAgent() throws Exception {
    String body =
        mockMvc
            .perform(
                get("/api/v1/agent/services-suivis")
                    .header("Authorization", "Bearer " + agentToken))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(body, "$.services");
  }

  @Test
  @DisplayName("Services actifs et ports en écoute, liste d'exclusion appliquée")
  void discoveredServicesAndPorts() throws Exception {
    sendSnapshot("[\"nginx\",\"postgresql\",\"systemd-journald\",\"getty@tty1\",\"ssh\"]");

    discovered(viewerJwt)
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.services[*].nom")
                .value(org.hamcrest.Matchers.contains("nginx", "postgresql", "ssh")))
        .andExpect(jsonPath("$.exclus").value(2))
        .andExpect(jsonPath("$.ports[0].port").value(22))
        .andExpect(jsonPath("$.ports[1].protocole").value("tcp6"))
        .andExpect(jsonPath("$.ports[2].port").value(443));
  }

  @Test
  @DisplayName(
      "Suivre un service : l'agent le relit, sans que sa configuration change ; arrêté, il reste affiché")
  void followingAServiceReachesTheAgent() throws Exception {
    sendSnapshot("[\"nginx\",\"postgresql\"]");
    String body =
        follow("nginx", adminJwt)
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.suivi").value(true))
            .andReturn()
            .getResponse()
            .getContentAsString();
    follow("nginx", adminJwt).andExpect(status().isCreated());
    assertThat(followedByAgent()).containsExactly("nginx");
    assertThat(
            jdbc.queryForObject(
                "SELECT type FROM monitored_service WHERE id = ?",
                String.class,
                UUID.fromString(JsonPath.read(body, "$.serviceId"))))
        .isEqualTo("AGENT_SERVICE");

    // nginx s'est arrêté : l'agent ne le voit plus actif, la plateforme le montre toujours suivi.
    sendSnapshot("[\"postgresql\"]");
    discovered(adminJwt)
        .andExpect(
            jsonPath("$.services[?(@.nom == 'nginx')].suivi")
                .value(org.hamcrest.Matchers.contains(true)));

    String serviceId = JsonPath.read(body, "$.serviceId");
    mockMvc
        .perform(
            delete("/api/v1/equipements/" + vm.getId() + "/services-suivis/" + serviceId)
                .header("Authorization", "Bearer " + adminJwt))
        .andExpect(status().isNoContent());
    assertThat(followedByAgent()).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_entry WHERE action IN ('AGENT_SERVICE_FOLLOWED', 'AGENT_SERVICE_UNFOLLOWED')",
                Integer.class))
        .isGreaterThanOrEqualTo(2);
  }

  @Test
  @DisplayName("Garde-fous : nom invalide, nom pris par une sonde, lecteur, compte d'un autre site")
  void followRules() throws Exception {
    sendSnapshot("[\"nginx\"]");
    follow("nginx; rm -rf /", adminJwt).andExpect(status().isBadRequest());
    services.saveAndFlush(new MonitoredService(vm, "portail", ServiceType.HTTPS));
    follow("portail", adminJwt).andExpect(status().isConflict());
    follow("nginx", viewerJwt).andExpect(status().isForbidden());
    // ADR 0058 : suivre un service est réservé à l'administrateur.
    follow("nginx", limitedJwt).andExpect(status().isForbidden());
    discovered(limitedJwt).andExpect(status().isNotFound());
    mockMvc
        .perform(
            delete("/api/v1/equipements/" + vm.getId() + "/services-suivis/" + UUID.randomUUID())
                .header("Authorization", "Bearer " + adminJwt))
        .andExpect(status().isNotFound());
    // Un jeton d'utilisateur n'ouvre pas la route de l'agent.
    mockMvc
        .perform(get("/api/v1/agent/services-suivis").header("Authorization", "Bearer " + adminJwt))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("Un agent ancien, sans services ni ports, reste accepté")
  void anOlderAgentIsStillAccepted() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/agent/snapshot")
                .header("Authorization", "Bearer " + agentToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"hostname\":\"vm-web-01\",\"os\":\"Linux\",\"cpuCount\":2,\"memoryTotal\":1,\"partial\":false,"
                        + "\"processes\":{\"byCpu\":[],\"byMemory\":[]}}"))
        .andExpect(status().isNoContent());
    discovered(adminJwt)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.services.length()").value(0));
  }
}

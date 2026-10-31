package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.snmp4j.security.AuthSHA;
import org.snmp4j.security.PrivAES128;
import org.snmp4j.security.UsmUser;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import sn.ept.transdic1.collection.service.DiscoveryService;
import sn.ept.transdic1.collection.service.Ipv4Range;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.EquipmentStatus;
import sn.ept.transdic1.inventory.domain.EquipmentType;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.SiteRepository;
import sn.ept.transdic1.security.domain.AppUser;
import sn.ept.transdic1.security.domain.Role;
import sn.ept.transdic1.security.repository.UserRepository;
import sn.ept.transdic1.support.SnmpTestAgent;

/**
 * Découverte automatique (FEAT-07) contre un vrai agent SNMP local : plage autorisée seulement,
 * équipements proposés sans création, une recherche à la fois, audit, rôles.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DiscoveryApiTests {
  private static final String PASSWORD = "Mot-de-passe-test-123";
  private static final String COMMUNITY = "tdic-discovery-community";
  private static final SnmpTestAgent AGENT = startAgent();

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private PasswordEncoder encoder;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private DiscoveryService discovery;

  private String admin;
  private String noc;
  private Site site;

  private static SnmpTestAgent startAgent() {
    try {
      SnmpTestAgent agent =
          new SnmpTestAgent(
              COMMUNITY,
              new UsmUser(
                  new OctetString("discuser"),
                  AuthSHA.ID,
                  new OctetString("auth-pass-12345"),
                  PrivAES128.ID,
                  new OctetString("priv-pass-12345")));
      agent.set("1.3.6.1.2.1.1.1.0", new OctetString("Cisco Catalyst 2960 switch (fictif)"));
      agent.set("1.3.6.1.2.1.1.2.0", new OID("1.3.6.1.4.1.9.1.716"));
      agent.set("1.3.6.1.2.1.1.5.0", new OctetString("sw-labo-fictif"));
      return agent;
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @AfterAll
  static void stopAgent() throws IOException {
    AGENT.close();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("collection.snmp.default-port", AGENT::port);
    registry.add("discovery.allowed-ranges", () -> "127.0.0.0/30, 192.0.2.0/29");
    registry.add("discovery.max-hosts", () -> 5);
    registry.add("discovery.host-timeout-ms", () -> 500);
    registry.add("DISCOVERY_TEST_COMMUNITY", () -> COMMUNITY);
    registry.add("DISCOVERY_TEST_WRONG", () -> "mauvaise-communaute");
    registry.add("DISCOVERY_TEST_V3", () -> "auth-pass-12345|priv-pass-12345");
  }

  @BeforeEach
  void data() throws Exception {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    users.save(
        new AppUser(
            "disc-adm-" + suffix, suffix + "a@example.test", encoder.encode(PASSWORD), Role.ADMIN));
    users.save(
        new AppUser(
            "disc-noc-" + suffix,
            suffix + "n@example.test",
            encoder.encode(PASSWORD),
            Role.NOC_OPERATOR));
    admin = login("disc-adm-" + suffix);
    noc = login("disc-noc-" + suffix);
    site = sites.save(new Site("DSC-" + suffix, "Site de découverte"));
  }

  @AfterEach
  void clean() {
    jdbc.update("DELETE FROM discovery_scan");
    jdbc.update("DELETE FROM equipment WHERE site_id = ?", site.getId());
    jdbc.update("DELETE FROM site WHERE id = ?", site.getId());
  }

  @Test
  void exposeLesPlagesAutoriseesEtLesLimites() throws Exception {
    mockMvc
        .perform(get("/api/v1/decouverte/parametres").header("Authorization", "Bearer " + admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.allowedRanges[0]").value("127.0.0.0/30"))
        .andExpect(jsonPath("$.allowedRanges[1]").value("192.0.2.0/29"))
        .andExpect(jsonPath("$.maxHosts").value(5));
  }

  @Test
  void trouveUnEquipementEtLeProposeSansLeCreer() throws Exception {
    Equipment known =
        equipments.save(
            new Equipment(
                "DSC-KNOWN-" + site.getCode(),
                "Déjà connu",
                "127.0.0.1",
                EquipmentType.SWITCH,
                EquipmentStatus.UP,
                site));
    long before = equipments.count();

    String id =
        start("127.0.0.1/32", "V2C", "env:DISCOVERY_TEST_COMMUNITY", null)
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.status").value("RUNNING"))
            .andExpect(jsonPath("$.hostsTotal").value(1))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String scan = awaitFinished(JsonPath.read(id, "$.id"));

    assertThat((String) JsonPath.read(scan, "$.status")).isEqualTo("COMPLETED");
    assertThat((Integer) JsonPath.read(scan, "$.hostsScanned")).isEqualTo(1);
    assertThat((String) JsonPath.read(scan, "$.candidates[0].address")).isEqualTo("127.0.0.1");
    assertThat((String) JsonPath.read(scan, "$.candidates[0].sysName")).isEqualTo("sw-labo-fictif");
    assertThat((String) JsonPath.read(scan, "$.candidates[0].suggestedType")).isEqualTo("SWITCH");
    assertThat((String) JsonPath.read(scan, "$.candidates[0].existingEquipmentId"))
        .isEqualTo(known.getId().toString());
    assertThat(equipments.count()).isEqualTo(before);
    assertThat(
            jdbc.queryForList(
                "SELECT action || ':' || coalesce(username, '-') FROM audit_entry WHERE target_type = 'DISCOVERY' ORDER BY performed_at",
                String.class))
        .hasSize(2)
        .allMatch(entry -> entry.contains(":disc-adm-"))
        .anyMatch(entry -> entry.startsWith("DISCOVERY_STARTED"))
        .anyMatch(entry -> entry.startsWith("DISCOVERY_FINISHED"));
  }

  @Test
  void trouveAussiEnSnmpV3() throws Exception {
    String body =
        start("127.0.0.1/32", "V3", "env:DISCOVERY_TEST_V3", "discuser")
            .andExpect(status().isAccepted())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String scan = awaitFinished(JsonPath.read(body, "$.id"));
    assertThat((String) JsonPath.read(scan, "$.candidates[0].sysName")).isEqualTo("sw-labo-fictif");
  }

  @Test
  void unEquipementQuiNeRepondPasNEstPasPropose() throws Exception {
    String body =
        start("127.0.0.0/30", "V2C", "env:DISCOVERY_TEST_WRONG", null)
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.hostsTotal").value(2))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String scan = awaitFinished(JsonPath.read(body, "$.id"));
    assertThat((String) JsonPath.read(scan, "$.status")).isEqualTo("COMPLETED");
    assertThat((java.util.List<?>) JsonPath.read(scan, "$.candidates")).isEmpty();
    mockMvc
        .perform(get("/api/v1/decouverte").header("Authorization", "Bearer " + admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].cidr").value("127.0.0.0/30"));
  }

  @Test
  void refuseUnePlageNonAutoriseeOuTropGrande() throws Exception {
    start("10.0.0.0/30", "V2C", "env:DISCOVERY_TEST_COMMUNITY", null)
        .andExpect(status().isForbidden());
    start("127.0.0.0/29", "V2C", "env:DISCOVERY_TEST_COMMUNITY", null)
        .andExpect(status().isForbidden());
    start("192.0.2.0/29", "V2C", "env:DISCOVERY_TEST_COMMUNITY", null)
        .andExpect(status().isBadRequest());
    start("pas-une-plage", "V2C", "env:DISCOVERY_TEST_COMMUNITY", null)
        .andExpect(status().isBadRequest());
    start("127.0.0.1/30", "V2C", "env:DISCOVERY_TEST_COMMUNITY", null)
        .andExpect(status().isBadRequest());
  }

  @Test
  void refuseUnSecretEnClairOuIntrouvable() throws Exception {
    start("127.0.0.1/32", "V2C", "public", null).andExpect(status().isBadRequest());
    start("127.0.0.1/32", "V2C", "env:DISCOVERY_ABSENT", null).andExpect(status().isBadRequest());
    start("127.0.0.1/32", "V3", "env:DISCOVERY_TEST_V3", " ").andExpect(status().isBadRequest());
  }

  @Test
  void uneSeuleRechercheALaFois() throws Exception {
    jdbc.update(
        "INSERT INTO discovery_scan(id, cidr, snmp_version, credential_ref, status, hosts_total, started_at) VALUES (?, '127.0.0.0/30', 'V2C', 'env:X', 'RUNNING', 2, now())",
        UUID.randomUUID());
    start("127.0.0.1/32", "V2C", "env:DISCOVERY_TEST_COMMUNITY", null)
        .andExpect(status().isConflict());
  }

  @Test
  void uneRechercheCoupeeParUnArretEstMarqueeInterrompue() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO discovery_scan(id, cidr, snmp_version, credential_ref, status, hosts_total, started_at) VALUES (?, '127.0.0.0/30', 'V2C', 'env:X', 'RUNNING', 2, now())",
        id);
    discovery.interruptStaleScans();
    assertThat(
            jdbc.queryForObject("SELECT status FROM discovery_scan WHERE id = ?", String.class, id))
        .isEqualTo("INTERRUPTED");
  }

  @Test
  void reserveeAuxAdministrateurs() throws Exception {
    mockMvc
        .perform(get("/api/v1/decouverte/parametres").header("Authorization", "Bearer " + noc))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            post("/api/v1/decouverte")
                .header("Authorization", "Bearer " + noc)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"cidr\":\"127.0.0.1/32\",\"snmpVersion\":\"V2C\",\"credentialReference\":\"env:DISCOVERY_TEST_COMMUNITY\"}"))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            get("/api/v1/decouverte/" + UUID.randomUUID())
                .header("Authorization", "Bearer " + admin))
        .andExpect(status().isNotFound());
  }

  @Test
  void lesPlagesSontAnalyseesSansResolutionDns() {
    assertThat(Ipv4Range.parse("192.0.2.0/30").hosts()).containsExactly("192.0.2.1", "192.0.2.2");
    assertThat(Ipv4Range.parse("192.0.2.4/31").hosts()).containsExactly("192.0.2.4", "192.0.2.5");
    assertThat(Ipv4Range.parse("192.0.2.7/32").hostCount()).isEqualTo(1);
    assertThat(Ipv4Range.parse("192.0.2.0/28").within(Ipv4Range.parse("192.0.2.0/24"))).isTrue();
    assertThat(Ipv4Range.parse("192.0.2.0/24").within(Ipv4Range.parse("192.0.2.0/28"))).isFalse();
    assertThat(Ipv4Range.parse("0.0.0.0/0").hostCount()).isEqualTo(4_294_967_294L);
    for (String invalid :
        new String[] {"192.0.2.0", "192.0.2.300/24", "192.0.2.0/33", "exemple.org/24", null}) {
      assertThatThrownBy(() -> Ipv4Range.parse(invalid))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  private ResultActions start(String cidr, String version, String credential, String identity)
      throws Exception {
    String json =
        "{\"cidr\":\""
            + cidr
            + "\",\"snmpVersion\":\""
            + version
            + "\",\"credentialReference\":\""
            + credential
            + "\""
            + (identity == null ? "" : ",\"securityIdentity\":\"" + identity + "\"")
            + "}";
    return mockMvc.perform(
        post("/api/v1/decouverte")
            .header("Authorization", "Bearer " + admin)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json));
  }

  private String awaitFinished(String id) throws Exception {
    for (int i = 0; i < 100; i++) {
      String body =
          mockMvc
              .perform(get("/api/v1/decouverte/" + id).header("Authorization", "Bearer " + admin))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();
      if (!"RUNNING".equals(JsonPath.read(body, "$.status"))) {
        return body;
      }
      Thread.sleep(100);
    }
    throw new AssertionError("recherche non terminée");
  }

  private String login(String username) throws Exception {
    String body =
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
}

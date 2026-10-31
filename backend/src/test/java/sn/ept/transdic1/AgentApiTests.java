package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
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
import sn.ept.transdic1.alerting.domain.AlertRule;
import sn.ept.transdic1.alerting.domain.ComparisonOperator;
import sn.ept.transdic1.alerting.domain.RuleEquipmentScope;
import sn.ept.transdic1.alerting.domain.Severity;
import sn.ept.transdic1.alerting.repository.AlertRepository;
import sn.ept.transdic1.alerting.repository.AlertRuleRepository;
import sn.ept.transdic1.alerting.repository.RuleEquipmentScopeRepository;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.EquipmentStatus;
import sn.ept.transdic1.inventory.domain.EquipmentType;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.SiteRepository;
import sn.ept.transdic1.monitoring.repository.MetricRepository;
import sn.ept.transdic1.security.domain.AppUser;
import sn.ept.transdic1.security.domain.Role;
import sn.ept.transdic1.security.repository.UserRepository;

/** Enregistrement des agents et réception de leurs mesures, de l'authentification aux alertes. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AgentApiTests {
  private static final String PASSWORD = "Mot-de-passe-test-123";

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private PasswordEncoder encoder;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private AlertRuleRepository rules;
  @Autowired private RuleEquipmentScopeRepository scopes;
  @Autowired private AlertRepository alerts;
  @Autowired private MetricRepository metrics;
  @Autowired private JdbcTemplate jdbc;

  private Site site;
  private Equipment equipment;
  private String adminJwt;
  private String viewerJwt;

  @BeforeEach
  void data() throws Exception {
    users.deleteAll();
    users.save(
        new AppUser(
            "agent-admin", "agent-admin@example.test", encoder.encode(PASSWORD), Role.ADMIN));
    users.save(
        new AppUser(
            "agent-viewer", "agent-viewer@example.test", encoder.encode(PASSWORD), Role.VIEWER));
    adminJwt = login("agent-admin");
    viewerJwt = login("agent-viewer");
    String unique = UUID.randomUUID().toString();
    site = sites.save(new Site("AGT-" + unique.substring(0, 8), "Site d'agent"));
    equipment =
        equipments.save(
            new Equipment(
                "AGT-EQ-" + unique,
                "Serveur d'agent",
                "10.243."
                    + (Math.floorMod(unique.hashCode(), 250) + 1)
                    + "."
                    + (Math.floorMod(unique.substring(9).hashCode(), 250) + 1),
                EquipmentType.SERVER,
                EquipmentStatus.UP,
                site));
  }

  /**
   * Ce test valide des données réelles (pas de transaction) : il les retire pour les autres tests.
   */
  @AfterEach
  void cleanUp() {
    jdbc.update("DELETE FROM alert WHERE equipment_id = ?", equipment.getId());
    jdbc.update(
        "DELETE FROM alert_rule WHERE id IN (SELECT rule_id FROM rule_equipment_scope"
            + " WHERE equipment_id = ?)",
        equipment.getId());
    jdbc.update("DELETE FROM metric WHERE equipment_id = ?", equipment.getId());
    jdbc.update("DELETE FROM collection_agent WHERE equipment_id = ?", equipment.getId());
    jdbc.update("DELETE FROM audit_entry WHERE target_id = ?", equipment.getId());
    jdbc.update("DELETE FROM equipment WHERE id = ?", equipment.getId());
    jdbc.update("DELETE FROM site WHERE id = ?", site.getId());
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

  private String register() throws Exception {
    var body =
        mockMvc
            .perform(
                post("/api/v1/equipements/" + equipment.getId() + "/agent")
                    .header("Authorization", "Bearer " + adminJwt))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.token").isNotEmpty())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(body, "$.token");
  }

  private static String measures(String value, Instant at) {
    return "{\"measures\":[{\"key\":\"cpu.usage\",\"value\":"
        + value
        + ",\"unit\":\"%\",\"quality\":\"GOOD\",\"collectedAt\":\""
        + at
        + "\"}]}";
  }

  private org.springframework.test.web.servlet.ResultActions send(String token, String json)
      throws Exception {
    var request =
        post("/api/v1/agent/measures").contentType(MediaType.APPLICATION_JSON).content(json);
    if (token != null) {
      request = request.header("Authorization", "Bearer " + token);
    }
    return mockMvc.perform(request);
  }

  @Test
  void anAdministratorReadsTheAgentStatusWithoutTheToken() throws Exception {
    String path = "/api/v1/equipements/" + equipment.getId() + "/agent";
    mockMvc
        .perform(get(path).header("Authorization", "Bearer " + adminJwt))
        .andExpect(status().isNotFound());

    String token = register();
    var body =
        mockMvc
            .perform(get(path).header("Authorization", "Bearer " + adminJwt))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ACTIVE"))
            .andExpect(jsonPath("$.identifier").value(org.hamcrest.Matchers.startsWith("agent-")))
            .andExpect(jsonPath("$.lastCommunicationAt").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(body).doesNotContain(token).doesNotContain("token");

    send(token, measures("1", Instant.now().minus(1, ChronoUnit.MINUTES)))
        .andExpect(status().isOk());
    mockMvc
        .perform(get(path).header("Authorization", "Bearer " + adminJwt))
        .andExpect(jsonPath("$.lastCommunicationAt").isNotEmpty());
    mockMvc
        .perform(get(path).header("Authorization", "Bearer " + viewerJwt))
        .andExpect(status().isForbidden());
  }

  @Test
  void anAdministratorRegistersAnAgentAndTheTokenIsNeverStoredInClear() throws Exception {
    String token = register();

    assertThat(token).startsWith("tdic1_");
    Integer clear =
        jdbc.queryForObject(
            "SELECT count(*) FROM collection_agent WHERE token_hash = ?", Integer.class, token);
    assertThat(clear).isZero();
    Integer hashed =
        jdbc.queryForObject(
            "SELECT count(*) FROM collection_agent WHERE equipment_id = ? AND status = 'ACTIVE'"
                + " AND token_hash IS NOT NULL",
            Integer.class,
            equipment.getId());
    assertThat(hashed).isEqualTo(1);
  }

  @Test
  void onlyAnAdministratorCanRegisterOrRevokeAnAgent() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/equipements/" + equipment.getId() + "/agent")
                .header("Authorization", "Bearer " + viewerJwt))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(post("/api/v1/equipements/" + equipment.getId() + "/agent"))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            delete("/api/v1/equipements/" + equipment.getId() + "/agent")
                .header("Authorization", "Bearer " + viewerJwt))
        .andExpect(status().isForbidden());
  }

  @Test
  void anAgentStoresMeasuresAndRecordsItsLastCommunication() throws Exception {
    String token = register();
    Instant at = Instant.now().minus(1, ChronoUnit.MINUTES);

    send(token, measures("42.5", at))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.stored").value(1))
        .andExpect(jsonPath("$.evaluated").value(1));

    var latest =
        metrics
            .findFirstByEquipmentIdAndKeyOrderByCollectedAtDesc(equipment.getId(), "cpu.usage")
            .orElseThrow();
    assertThat(latest.getValue()).isEqualByComparingTo("42.5");
    Integer communicated =
        jdbc.queryForObject(
            "SELECT count(*) FROM collection_agent WHERE equipment_id = ?"
                + " AND last_communication_at IS NOT NULL",
            Integer.class,
            equipment.getId());
    assertThat(communicated).isEqualTo(1);
  }

  @Test
  void aBreachSentByAnAgentOpensAnAlert() throws Exception {
    var rule =
        new AlertRule(
            "agt-" + UUID.randomUUID(),
            "cpu.usage",
            ComparisonOperator.GREATER_THAN,
            new BigDecimal("90"),
            Duration.ZERO,
            Severity.CRITICAL);
    rule = rules.save(rule);
    scopes.save(new RuleEquipmentScope(rule, equipment));
    String token = register();

    send(token, measures("97", Instant.now().minus(1, ChronoUnit.MINUTES)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.alertsOpened").value(1));

    assertThat(
            alerts
                .findByEquipmentIdOrderByTriggeredAtDesc(
                    equipment.getId(), org.springframework.data.domain.Pageable.unpaged())
                .getContent())
        .hasSize(1);
  }

  @Test
  void invalidMissingOrUserTokensAreRefused() throws Exception {
    send(null, measures("1", Instant.now())).andExpect(status().isUnauthorized());
    send("tdic1_inconnu", measures("1", Instant.now())).andExpect(status().isUnauthorized());
    // Le JWT d'un administrateur n'ouvre pas l'API des agents.
    send(adminJwt, measures("1", Instant.now())).andExpect(status().isUnauthorized());
  }

  @Test
  void anAgentTokenDoesNotOpenTheUserApi() throws Exception {
    String token = register();

    mockMvc
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/api/v1/equipements")
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void registeringAgainRevokesThePreviousAgent() throws Exception {
    String first = register();
    String second = register();

    send(first, measures("1", Instant.now().minus(1, ChronoUnit.MINUTES)))
        .andExpect(status().isUnauthorized());
    send(second, measures("1", Instant.now().minus(1, ChronoUnit.MINUTES)))
        .andExpect(status().isOk());
  }

  @Test
  void revokingStopsTheAgent() throws Exception {
    String token = register();

    mockMvc
        .perform(
            delete("/api/v1/equipements/" + equipment.getId() + "/agent")
                .header("Authorization", "Bearer " + adminJwt))
        .andExpect(status().isNoContent());

    send(token, measures("1", Instant.now().minus(1, ChronoUnit.MINUTES)))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            delete("/api/v1/equipements/" + equipment.getId() + "/agent")
                .header("Authorization", "Bearer " + adminJwt))
        .andExpect(status().isNotFound());
  }

  @Test
  void malformedBatchesAreRejected() throws Exception {
    String token = register();

    send(token, "{\"measures\":[]}").andExpect(status().isBadRequest());
    send(token, measures("1", Instant.now().plus(1, ChronoUnit.HOURS)))
        .andExpect(status().isBadRequest());
    send(token, measures("1", Instant.now()).replace("GOOD", "EXCELLENT"))
        .andExpect(status().isBadRequest());
  }

  /**
   * Contrat avec l'agent Trans-DIC1 (agent/) : le lot qu'il produit, figé dans un fichier que ses
   * propres tests comparent à sa sortie, est accepté tel quel. Une mesure BAD est enregistrée sans
   * être évaluée.
   */
  @Test
  void leLotProduitParLAgentEstAccepteTelQuel() throws Exception {
    String token = register();
    String payload =
        java.nio.file.Files.readString(
            java.nio.file.Path.of("..", "agent", "tests", "fixtures", "agent-payload.json"));

    send(token, payload)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.stored").value(4))
        .andExpect(jsonPath("$.evaluated").value(3));

    assertThat(
            jdbc.queryForList(
                "SELECT metric_key || ':' || quality FROM metric WHERE equipment_id = ?"
                    + " ORDER BY metric_key",
                String.class,
                equipment.getId()))
        .containsExactly(
            "availability:GOOD", "cpu.usage:GOOD", "disk.usage:BAD", "memory.usage:GOOD");
  }
}

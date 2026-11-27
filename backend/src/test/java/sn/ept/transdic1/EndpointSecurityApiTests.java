package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.collection.service.AgentRegistrationService;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.EquipmentStatus;
import sn.ept.transdic1.inventory.domain.EquipmentType;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.SiteRepository;

/**
 * EDR léger (ADR 0058) de bout en bout : l'agent envoie la télémétrie de son poste avec son jeton,
 * le premier rapport fixe la ligne de base, les suivants ouvrent constats et alertes de sécurité
 * rattachés à MITRE ATT&CK, puis un opérateur juge chaque constat.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class EndpointSecurityApiTests {
  private static final String HASH_A = "a".repeat(64);
  private static final String HASH_B = "b".repeat(64);

  @Autowired private MockMvc mockMvc;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private AgentRegistrationService registration;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EntityManager em;

  private final String suffix = UUID.randomUUID().toString().substring(0, 8);
  private Equipment server;
  private String agentToken;

  @BeforeEach
  void data() {
    Site site = sites.saveAndFlush(new Site("EDR-" + suffix, "Site EDR"));
    server =
        equipments.saveAndFlush(
            new Equipment(
                "EDR-" + UUID.randomUUID(),
                "Serveur web EDR",
                "10.95." + (Math.floorMod(suffix.hashCode(), 250) + 1) + ".10",
                EquipmentType.SERVER,
                EquipmentStatus.UP,
                site));
    agentToken = registration.register(server.getId()).token();
    em.flush();
  }

  private static String cleanReport() {
    return """
        {"os":"Linux","partial":false,
         "processes":[{"name":"nginx","path":"/usr/sbin/nginx"},{"name":"kworker","path":null}],
         "persistence":[{"kind":"cron","name":"/etc/crontab","fingerprint":"%s","detail":null}],
         "accounts":[{"name":"root","privileged":true,"superuser":true}],
         "integrity":[{"path":"/etc/hosts","present":true,"sha256":"%s"}],
         "connections":[{"address":"203.0.113.9","port":443}]}"""
        .formatted(HASH_A, HASH_A);
  }

  private static String compromisedReport() {
    return """
        {"os":"Linux","partial":false,
         "processes":[{"name":"nginx","path":"/usr/sbin/nginx"},{"name":"xmrig","path":"/tmp/.x/xmrig"}],
         "persistence":[{"kind":"cron","name":"/etc/crontab","fingerprint":"%s","detail":null},
                        {"kind":"cron","name":"/etc/cron.d/maj","fingerprint":"%s","detail":null}],
         "accounts":[{"name":"root","privileged":true,"superuser":true},
                     {"name":"pirate","privileged":true,"superuser":false}],
         "integrity":[{"path":"/etc/hosts","present":true,"sha256":"%s"}],
         "connections":[{"address":"198.51.100.7","port":4444}]}"""
        .formatted(HASH_A, HASH_B, HASH_B);
  }

  private ResultActions send(String body) throws Exception {
    return mockMvc.perform(
        post("/api/v1/agent/securite-poste")
            .header("Authorization", "Bearer " + agentToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  private long securityAlerts() {
    em.flush();
    return jdbc.queryForObject(
        "SELECT count(*) FROM alert WHERE equipment_id = ? AND type = 'SECURITY'"
            + " AND occurrence_key LIKE 'security:poste:%'",
        Long.class, server.getId());
  }

  @Test
  @DisplayName(
      "Premier rapport : ligne de base, aucun changement signalé ; ensuite constats et alertes")
  void baselineThenFindings() throws Exception {
    send(cleanReport())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.baseline").value(true))
        .andExpect(jsonPath("$.findings").value(0));
    assertThat(securityAlerts()).isZero();

    send(compromisedReport())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.baseline").value(false))
        .andExpect(jsonPath("$.findings").value(6));
    em.flush();
    List<String> rules =
        jdbc.queryForList(
            "SELECT rule FROM endpoint_finding WHERE equipment_id = ? ORDER BY rule",
            String.class,
            server.getId());
    assertThat(rules)
        .containsExactly(
            "FILE_CHANGED",
            "NEW_PERSISTENCE",
            "NEW_PRIVILEGED_ACCOUNT",
            "PROCESS_OFFENSIVE_TOOL",
            "PROCESS_TEMP_PATH",
            "SUSPICIOUS_PORT");
    assertThat(securityAlerts()).isEqualTo(6);
    String message =
        jdbc.queryForObject(
            "SELECT message FROM alert WHERE equipment_id = ? AND message LIKE '%xmrig%'"
                + " AND message LIKE '%mineur%'",
            String.class, server.getId());
    assertThat(message).startsWith("Poste : ").endsWith("[MITRE ATT&CK T1496]");

    // Le même rapport renvoyé ne rouvre rien : un sujet, un constat.
    send(compromisedReport()).andExpect(jsonPath("$.findings").value(0));
    assertThat(securityAlerts()).isEqualTo(6);
  }

  @Test
  @DisplayName(
      "Écran : postes et constats ; jugement par un opérateur, tracé ; un lecteur ne juge pas")
  void overviewAndDecision() throws Exception {
    send(cleanReport()).andExpect(status().isOk());
    send(compromisedReport()).andExpect(status().isOk());
    em.flush();

    String body =
        mockMvc
            .perform(get("/api/v1/securite/postes").with(user("lecteur").roles("VIEWER")))
            .andExpect(status().isOk())
            .andExpect(
                jsonPath("$.postes[?(@.equipementNom == 'Serveur web EDR')].rapports").value(2))
            .andReturn()
            .getResponse()
            .getContentAsString();
    List<String> ids =
        JsonPath.read(
            body,
            "$.constats[?(@.equipementNom == 'Serveur web EDR' && @.regle == 'NEW_PERSISTENCE')].id");
    assertThat(ids).hasSize(1);
    List<String> urls =
        JsonPath.read(body, "$.constats[?(@.regle == 'NEW_PERSISTENCE')].techniqueUrl");
    assertThat(urls).contains("https://attack.mitre.org/techniques/T1053/003/");

    String id = ids.getFirst();
    mockMvc
        .perform(
            post("/api/v1/securite/postes/constats/" + id + "/decision")
                .with(user("lecteur").roles("VIEWER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"etat\":\"LEGITIMATE\"}"))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            post("/api/v1/securite/postes/constats/" + id + "/decision")
                .with(user("noc").roles("NOC_OPERATOR"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"etat\":\"OPEN\"}"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            post("/api/v1/securite/postes/constats/" + id + "/decision")
                .with(user("noc").roles("NOC_OPERATOR"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"etat\":\"LEGITIMATE\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.etat").value("LEGITIMATE"))
        .andExpect(jsonPath("$.decidePar").value("noc"));
    em.flush();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_entry WHERE action = 'ENDPOINT_FINDING_DECIDED'"
                    + " AND target_id = ?::uuid",
                Long.class,
                id))
        .isEqualTo(1);
    mockMvc
        .perform(
            post("/api/v1/securite/postes/constats/" + UUID.randomUUID() + "/decision")
                .with(user("noc").roles("NOC_OPERATOR"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"etat\":\"MALICIOUS\"}"))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("Seul un agent authentifié envoie un rapport, et un rapport mal formé est refusé")
  void authenticationAndValidation() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/agent/securite-poste")
                .header("Authorization", "Bearer tdic1_jeton-inconnu-0000000000000000000")
                .contentType(MediaType.APPLICATION_JSON)
                .content(cleanReport()))
        .andExpect(status().isUnauthorized());
    send("{\"os\":\"Linux\",\"connections\":[{\"address\":\"203.0.113.9\",\"port\":70000}]}")
        .andExpect(status().isBadRequest());
    send("{\"os\":\"Linux\",\"persistence\":[{\"kind\":\"rootkit\",\"name\":\"x\"}]}")
        .andExpect(status().isBadRequest());
  }
}

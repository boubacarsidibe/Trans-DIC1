package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.alerting.domain.Alert;
import sn.ept.transdic1.alerting.domain.AlertChangedEvent;
import sn.ept.transdic1.alerting.domain.AlertEventType;
import sn.ept.transdic1.alerting.domain.AlertRule;
import sn.ept.transdic1.alerting.domain.AlertState;
import sn.ept.transdic1.alerting.domain.AlertType;
import sn.ept.transdic1.alerting.domain.ComparisonOperator;
import sn.ept.transdic1.alerting.domain.Severity;
import sn.ept.transdic1.alerting.repository.AlertRepository;
import sn.ept.transdic1.alerting.repository.AlertRuleRepository;
import sn.ept.transdic1.collection.service.AgentRegistrationService;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.EquipmentStatus;
import sn.ept.transdic1.inventory.domain.EquipmentType;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.SiteRepository;
import sn.ept.transdic1.remediation.service.RemediationService;

/**
 * Actions correctives (ADR 0050), de bout en bout : l'agent annonce ce qu'il sait faire, la règle
 * s'ouvre, l'action part (ou attend une approbation), l'agent la prend à son passage et rend
 * compte.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class RemediationApiTests {
  private static final String ACTION = "redemarrer-nginx";

  @Autowired private MockMvc mockMvc;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private AlertRuleRepository rules;
  @Autowired private AlertRepository alerts;
  @Autowired private AgentRegistrationService registration;
  @Autowired private RemediationService remediation;
  @Autowired private ApplicationEventPublisher events;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private jakarta.persistence.EntityManager em;

  private final String suffix = UUID.randomUUID().toString().substring(0, 8);
  private Equipment server;
  private AlertRule rule;
  private String agentToken;

  @BeforeEach
  void data() throws Exception {
    Site site = sites.saveAndFlush(new Site("REM-" + suffix, "Site remédiation"));
    server =
        equipments.saveAndFlush(
            new Equipment(
                "REM-" + UUID.randomUUID(),
                "Serveur web",
                "10.97." + (Math.floorMod(suffix.hashCode(), 250) + 1) + ".10",
                EquipmentType.SERVER,
                EquipmentStatus.UP,
                site));
    rule =
        rules.saveAndFlush(
            new AlertRule(
                "Service nginx arrêté " + suffix,
                "service.up",
                ComparisonOperator.LESS_THAN,
                BigDecimal.ONE,
                Duration.ZERO,
                Severity.CRITICAL));
    agentToken = registration.register(server.getId()).token();
    em.flush();
    // L'agent annonce ce qu'il sait faire : un nom et une description, jamais la commande.
    mockMvc
        .perform(
            post("/api/v1/agent/actions")
                .header("Authorization", "Bearer " + agentToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"available\":[{\"name\":\""
                        + ACTION
                        + "\",\"description\":\"Redémarre nginx\"}]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.jobs.length()").value(0));
  }

  private Alert openAlert() {
    Alert alert =
        new Alert(
            server,
            "rem:" + UUID.randomUUID(),
            AlertType.THRESHOLD,
            AlertState.OPEN,
            Severity.CRITICAL,
            "Service nginx arrêté",
            Instant.now());
    alert.setRule(rule);
    return alerts.saveAndFlush(alert);
  }

  private void policy(String mode) throws Exception {
    mockMvc
        .perform(
            put("/api/v1/regles-alertes/" + rule.getId() + "/action-corrective")
                .with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"actionName\":\""
                        + ACTION
                        + "\",\"mode\":\""
                        + mode
                        + "\",\"cooldownMinutes\":30}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.actionName").value(ACTION))
        .andExpect(jsonPath("$.mode").value(mode));
  }

  private String poll() throws Exception {
    return mockMvc
        .perform(
            post("/api/v1/agent/actions")
                .header("Authorization", "Bearer " + agentToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"available\":[{\"name\":\"" + ACTION + "\"}]}"))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  @Test
  @DisplayName("Mode automatique : l'action part à l'ouverture, l'agent la prend, puis rend compte")
  void automaticRemediationEndToEnd() throws Exception {
    policy("AUTO");
    Alert alert = openAlert();
    events.publishEvent(new AlertChangedEvent(alert.getId(), AlertEventType.OPENED));
    em.flush();

    mockMvc
        .perform(
            get("/api/v1/alertes/" + alert.getId() + "/actions-correctives")
                .with(user("viewer").roles("VIEWER")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.agentActive").value(true))
        .andExpect(jsonPath("$.available[0].name").value(ACTION))
        .andExpect(jsonPath("$.policy.mode").value("AUTO"))
        .andExpect(jsonPath("$.jobs[0].state").value("QUEUED"))
        .andExpect(jsonPath("$.jobs[0].trigger").value("AUTO"));

    String polled = poll();
    assertThat(JsonPath.<Integer>read(polled, "$.jobs.length()")).isEqualTo(1);
    assertThat(JsonPath.<String>read(polled, "$.jobs[0].action")).isEqualTo(ACTION);
    String jobId = JsonPath.read(polled, "$.jobs[0].id");
    // Prise une fois : le passage suivant ne la redonne pas.
    assertThat(JsonPath.<Integer>read(poll(), "$.jobs.length()")).isZero();

    mockMvc
        .perform(
            post("/api/v1/agent/actions/" + jobId + "/resultat")
                .header("Authorization", "Bearer " + agentToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"exitCode\":0,\"output\":\"nginx redémarré\",\"durationMs\":840}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("SUCCEEDED"))
        .andExpect(jsonPath("$.output").value("nginx redémarré"));
    // Un second compte rendu est refusé : une exécution terminée ne change plus.
    mockMvc
        .perform(
            post("/api/v1/agent/actions/" + jobId + "/resultat")
                .header("Authorization", "Bearer " + agentToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"exitCode\":1}"))
        .andExpect(status().isConflict());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_entry WHERE action = 'REMEDIATION_SUCCEEDED'"
                    + " AND target_id = ?",
                Integer.class,
                UUID.fromString(jobId)))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("Le délai de grâce empêche une alerte qui clignote de relancer l'action en boucle")
  void cooldownPreventsLoops() throws Exception {
    policy("AUTO");
    Alert first = openAlert();
    assertThat(remediation.trigger(first.getId())).isPresent();
    em.flush();
    // L'alerte se ferme puis se rouvre aussitôt (elle clignote) ; l'action précédente est finie.
    jdbc.update(
        "UPDATE remediation_job SET state = 'EXPIRED' WHERE equipment_id = ?", server.getId());
    jdbc.update(
        "UPDATE alert SET state = 'RESOLVED', resolved_at = now(), resolution_type = 'AUTOMATIC'"
            + " WHERE id = ?",
        first.getId());
    em.clear();
    assertThat(remediation.trigger(openAlert().getId())).isEmpty();
  }

  @Test
  @DisplayName("Mode approbation : rien ne part tant qu'un opérateur n'a pas approuvé")
  void approvalMode() throws Exception {
    policy("APPROVAL");
    Alert alert = openAlert();
    var job = remediation.trigger(alert.getId()).orElseThrow();
    em.flush();
    assertThat(JsonPath.<Integer>read(poll(), "$.jobs.length()")).isZero();

    mockMvc
        .perform(
            post("/api/v1/actions-correctives/" + job.getId() + "/approbation")
                .with(user("viewer").roles("VIEWER")))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            post("/api/v1/actions-correctives/" + job.getId() + "/approbation")
                .with(user("noc").roles("NOC_OPERATOR")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("QUEUED"))
        .andExpect(jsonPath("$.decidedBy").value("noc"));
    assertThat(JsonPath.<Integer>read(poll(), "$.jobs.length()")).isEqualTo(1);
  }

  @Test
  @DisplayName("Lancement manuel : une action que l'agent n'annonce pas est refusée")
  void manualLaunch() throws Exception {
    Alert alert = openAlert();
    mockMvc
        .perform(
            post("/api/v1/alertes/" + alert.getId() + "/actions-correctives")
                .with(user("noc").roles("NOC_OPERATOR"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"actionName\":\"rm-rf\"}"))
        .andExpect(status().isConflict());
    mockMvc
        .perform(
            post("/api/v1/alertes/" + alert.getId() + "/actions-correctives")
                .with(user("noc").roles("NOC_OPERATOR"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"actionName\":\"" + ACTION + "\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.trigger").value("MANUAL"))
        .andExpect(jsonPath("$.requestedBy").value("noc"));
    // La même action ne tourne jamais deux fois en même temps sur un équipement.
    mockMvc
        .perform(
            post("/api/v1/alertes/" + alert.getId() + "/actions-correctives")
                .with(user("noc").roles("NOC_OPERATOR"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"actionName\":\"" + ACTION + "\"}"))
        .andExpect(status().isConflict());
  }

  @Test
  @DisplayName("Une demande que l'agent ne prend pas à temps expire")
  void staleRequestsExpire() throws Exception {
    policy("AUTO");
    var job = remediation.trigger(openAlert().getId()).orElseThrow();
    em.flush();
    assertThat(remediation.expireStale(Instant.now().plus(Duration.ofHours(1)))).isEqualTo(1);
    em.flush();
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM remediation_job WHERE id = ?", String.class, job.getId()))
        .isEqualTo("EXPIRED");
  }

  @Test
  @DisplayName("Un nom d'action invalide est refusé dès la politique")
  void invalidActionName() throws Exception {
    mockMvc
        .perform(
            put("/api/v1/regles-alertes/" + rule.getId() + "/action-corrective")
                .with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"actionName\":\"rm -rf /\",\"mode\":\"AUTO\"}"))
        .andExpect(status().isBadRequest());
  }
}

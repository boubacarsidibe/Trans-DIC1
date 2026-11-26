package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.alerting.domain.Alert;
import sn.ept.transdic1.alerting.domain.AlertRule;
import sn.ept.transdic1.alerting.domain.AlertState;
import sn.ept.transdic1.alerting.domain.AlertType;
import sn.ept.transdic1.alerting.domain.ComparisonOperator;
import sn.ept.transdic1.alerting.domain.Severity;
import sn.ept.transdic1.alerting.repository.AlertRepository;
import sn.ept.transdic1.alerting.repository.AlertRuleRepository;
import sn.ept.transdic1.collection.service.AgentRegistrationService;
import sn.ept.transdic1.common.alerting.RemediationNeedsHuman;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.EquipmentStatus;
import sn.ept.transdic1.inventory.domain.EquipmentType;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.SiteRepository;
import sn.ept.transdic1.remediation.domain.RemediationJob;
import sn.ept.transdic1.remediation.domain.RemediationState;
import sn.ept.transdic1.remediation.domain.RemediationVerification;
import sn.ept.transdic1.remediation.repository.RemediationJobRepository;
import sn.ept.transdic1.remediation.service.RemediationService;

/**
 * Correction à distance enrichie (ADR 0057) : vérification après l'action, limite de fréquence qui
 * impose l'approbation, jamais d'action sur un équipement réseau.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@RecordApplicationEvents
class RemediationVerificationTests {
  private static final String ACTION = "redemarrer-nginx";

  @Autowired private MockMvc mockMvc;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private AlertRuleRepository rules;
  @Autowired private AlertRepository alerts;
  @Autowired private AgentRegistrationService registration;
  @Autowired private RemediationService remediation;
  @Autowired private RemediationJobRepository jobs;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EntityManager em;
  @Autowired private ApplicationEvents published;

  private final String suffix = UUID.randomUUID().toString().substring(0, 8);
  private Site site;
  private Equipment server;
  private AlertRule rule;
  private String agentToken;

  @BeforeEach
  void data() throws Exception {
    site = sites.saveAndFlush(new Site("RV-" + suffix, "Site vérification"));
    server = equipment(EquipmentType.SERVER);
    rule =
        rules.saveAndFlush(
            new AlertRule(
                "nginx arrêté " + suffix,
                "service.up",
                ComparisonOperator.LESS_THAN,
                BigDecimal.ONE,
                Duration.ZERO,
                Severity.CRITICAL));
    agentToken = registration.register(server.getId()).token();
    em.flush();
    poll();
  }

  private Equipment equipment(EquipmentType type) {
    String unique = UUID.randomUUID().toString();
    return equipments.saveAndFlush(
        new Equipment(
            "RV-" + unique,
            "Machine " + type,
            "10.96."
                + (Math.floorMod(unique.hashCode(), 250) + 1)
                + "."
                + (Math.floorMod(unique.substring(9).hashCode(), 250) + 1),
            type,
            EquipmentStatus.UP,
            site));
  }

  private Alert openAlert(Equipment on) {
    Alert alert =
        new Alert(
            on,
            "rv:" + UUID.randomUUID(),
            AlertType.THRESHOLD,
            AlertState.OPEN,
            Severity.CRITICAL,
            "nginx arrêté",
            Instant.now());
    alert.setRule(rule);
    return alerts.saveAndFlush(alert);
  }

  private void policy(String body) throws Exception {
    mockMvc
        .perform(
            put("/api/v1/regles-alertes/" + rule.getId() + "/action-corrective")
                .with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"actionName\":\"" + ACTION + "\",\"mode\":\"AUTO\"," + body + "}"))
        .andExpect(status().isOk());
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

  /** Une ouverture, l'agent prend l'action et rend compte avec ce code. */
  private RemediationJob runOnce(Alert alert, int exitCode) throws Exception {
    RemediationJob job = remediation.trigger(alert.getId()).orElseThrow();
    em.flush();
    String polled = poll();
    assertThat(JsonPath.<String>read(polled, "$.jobs[0].id")).isEqualTo(job.getId().toString());
    mockMvc
        .perform(
            post("/api/v1/agent/actions/" + job.getId() + "/resultat")
                .header("Authorization", "Bearer " + agentToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"exitCode\":" + exitCode + "}"))
        .andExpect(status().isOk());
    em.flush();
    em.clear();
    return jobs.findById(job.getId()).orElseThrow();
  }

  /** Une panne complète : ouverture, action, puis retour à la normale (alerte close). */
  private RemediationJob cycle() throws Exception {
    Alert alert = openAlert(server);
    RemediationJob job = runOnce(alert, 0);
    close(alert);
    return job;
  }

  private void close(Alert alert) {
    Alert reloaded = alerts.findById(alert.getId()).orElseThrow();
    reloaded.setState(AlertState.ARCHIVED);
    alerts.saveAndFlush(reloaded);
  }

  private long audits(String action) {
    em.flush();
    return jdbc.queryForObject(
        "SELECT count(*) FROM audit_entry WHERE action = ?", Long.class, action);
  }

  private List<RemediationNeedsHuman> needsHuman(Alert alert) {
    return published.stream(RemediationNeedsHuman.class)
        .filter(e -> e.alertId().equals(alert.getId()))
        .toList();
  }

  @Test
  @DisplayName("Vérification : alerte résolue à l'échéance, la correction est efficace")
  void effectiveWhenAlertResolved() throws Exception {
    policy("\"cooldownMinutes\":0,\"verifyMinutes\":10");
    Alert alert = openAlert(server);
    RemediationJob job = runOnce(alert, 0);
    assertThat(job.getVerifyDueAt()).isNotNull();
    assertThat(job.getVerification()).isNull();

    // Pas encore l'échéance : rien n'est jugé.
    assertThat(remediation.verifyDue(Instant.now())).isZero();

    Alert reloaded = alerts.findById(alert.getId()).orElseThrow();
    reloaded.setState(AlertState.ARCHIVED);
    alerts.saveAndFlush(reloaded);
    long effectiveBefore = audits("REMEDIATION_EFFECTIVE");
    assertThat(remediation.verifyDue(Instant.now().plus(11, ChronoUnit.MINUTES))).isEqualTo(1);
    em.flush();
    em.clear();

    RemediationJob verified = jobs.findById(job.getId()).orElseThrow();
    assertThat(verified.getVerification()).isEqualTo(RemediationVerification.EFFECTIVE);
    assertThat(verified.getVerifiedAt()).isNotNull();
    assertThat(audits("REMEDIATION_EFFECTIVE")).isEqualTo(effectiveBefore + 1);
    assertThat(needsHuman(alert)).isEmpty();
    // Jugée une fois : un second passage ne la reprend pas.
    assertThat(remediation.verifyDue(Instant.now().plus(20, ChronoUnit.MINUTES))).isZero();
  }

  @Test
  @DisplayName(
      "Vérification : alerte toujours ouverte, inefficace, astreinte prévenue, pas relancée")
  void ineffectiveWhenAlertStillOpen() throws Exception {
    policy("\"cooldownMinutes\":0,\"verifyMinutes\":5");
    Alert alert = openAlert(server);
    RemediationJob job = runOnce(alert, 0);

    assertThat(remediation.verifyDue(Instant.now().plus(6, ChronoUnit.MINUTES))).isEqualTo(1);
    em.flush();
    em.clear();

    assertThat(jobs.findById(job.getId()).orElseThrow().getVerification())
        .isEqualTo(RemediationVerification.INEFFECTIVE);
    assertThat(needsHuman(alert))
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.factKey()).isEqualTo("inefficace:" + job.getId());
              assertThat(e.reason()).contains(ACTION).contains("inefficace");
            });

    // La panne revient : l'action ne repart plus seule, elle attend une approbation motivée.
    close(alert);
    Alert again = openAlert(server);
    RemediationJob next = remediation.trigger(again.getId()).orElseThrow();
    assertThat(next.getState()).isEqualTo(RemediationState.PENDING_APPROVAL);
    assertThat(next.getApprovalReason()).contains("inefficace");
    assertThat(needsHuman(again)).hasSize(1);
  }

  @Test
  @DisplayName("Vérification : une action en échec est inefficace tout de suite")
  void failedActionIsIneffectiveAtOnce() throws Exception {
    policy("\"cooldownMinutes\":0,\"verifyMinutes\":15");
    Alert alert = openAlert(server);
    RemediationJob job = runOnce(alert, 3);

    assertThat(job.getState()).isEqualTo(RemediationState.FAILED);
    assertThat(job.getVerification()).isEqualTo(RemediationVerification.INEFFECTIVE);
    assertThat(job.getVerifyDueAt()).isNull();
    assertThat(needsHuman(alert))
        .singleElement()
        .satisfies(e -> assertThat(e.reason()).contains("code 3"));
  }

  @Test
  @DisplayName("Sans délai de vérification, rien n'est jugé (comportement de l'ADR 0050)")
  void noVerificationByDefault() throws Exception {
    policy("\"cooldownMinutes\":0");
    RemediationJob job = runOnce(openAlert(server), 0);
    assertThat(job.getVerifyDueAt()).isNull();
    assertThat(job.getVerification()).isNull();
  }

  @Test
  @DisplayName("Limite de fréquence : au-delà de N exécutions automatiques, approbation imposée")
  void frequencyLimitForcesApproval() throws Exception {
    policy("\"cooldownMinutes\":0,\"maxAutoRuns\":2,\"windowHours\":6");
    cycle();
    cycle();
    long forcedBefore = audits("REMEDIATION_FORCED_APPROVAL");

    Alert third = openAlert(server);
    RemediationJob job = remediation.trigger(third.getId()).orElseThrow();

    assertThat(job.getState()).isEqualTo(RemediationState.PENDING_APPROVAL);
    assertThat(job.getApprovalReason()).contains("limite de fréquence").contains("2 exécutions");
    assertThat(audits("REMEDIATION_FORCED_APPROVAL")).isEqualTo(forcedBefore + 1);
    assertThat(needsHuman(third))
        .singleElement()
        .satisfies(e -> assertThat(e.reason()).contains("en attente d'approbation"));
    em.flush();
    // L'agent ne la reçoit pas tant que personne n'a approuvé.
    assertThat(JsonPath.<Integer>read(poll(), "$.jobs.length()")).isZero();

    mockMvc
        .perform(
            post("/api/v1/actions-correctives/" + job.getId() + "/approbation")
                .with(user("noc").roles("NOC_OPERATOR")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("QUEUED"))
        .andExpect(jsonPath("$.approvalReason").value(job.getApprovalReason()));
  }

  @Test
  @DisplayName("Limite de fréquence : les exécutions hors fenêtre ne comptent plus")
  void runsOutsideWindowDoNotCount() throws Exception {
    policy("\"cooldownMinutes\":0,\"maxAutoRuns\":1,\"windowHours\":1");
    RemediationJob first = cycle();
    jdbc.update(
        "UPDATE remediation_job SET created_at = now() - interval '2 hours' WHERE id = ?",
        first.getId());

    RemediationJob next = remediation.trigger(openAlert(server).getId()).orElseThrow();
    assertThat(next.getState()).isEqualTo(RemediationState.QUEUED);
    assertThat(next.getApprovalReason()).isNull();
  }

  @Test
  @DisplayName("Jamais d'action corrective sur un routeur, un commutateur ou une borne Wi-Fi")
  void networkEquipmentRefused() throws Exception {
    policy("\"cooldownMinutes\":0");
    for (EquipmentType type :
        List.of(EquipmentType.ROUTER, EquipmentType.SWITCH, EquipmentType.WIFI_ACCESS_POINT)) {
      Equipment device = equipment(type);
      Alert alert = openAlert(device);
      assertThat(remediation.trigger(alert.getId())).isEmpty();
      mockMvc
          .perform(
              post("/api/v1/alertes/" + alert.getId() + "/actions-correctives")
                  .with(user("noc").roles("NOC_OPERATOR"))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"actionName\":\"" + ACTION + "\"}"))
          .andExpect(status().isConflict())
          .andExpect(
              jsonPath("$.error")
                  .value("Aucune action corrective n'est permise sur un équipement réseau."));
    }
  }

  @Test
  @DisplayName("Politique : bornes du délai de vérification et de la limite refusées")
  void policyBounds() throws Exception {
    for (String body :
        List.of(
            "\"verifyMinutes\":241",
            "\"maxAutoRuns\":0",
            "\"windowHours\":0",
            "\"windowHours\":169")) {
      mockMvc
          .perform(
              put("/api/v1/regles-alertes/" + rule.getId() + "/action-corrective")
                  .with(user("admin").roles("ADMIN"))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"actionName\":\"" + ACTION + "\",\"mode\":\"AUTO\"," + body + "}"))
          .andExpect(status().isBadRequest());
    }
    mockMvc
        .perform(
            put("/api/v1/regles-alertes/" + rule.getId() + "/action-corrective")
                .with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"actionName\":\""
                        + ACTION
                        + "\",\"mode\":\"AUTO\",\"verifyMinutes\":30,\"maxAutoRuns\":5,"
                        + "\"windowHours\":12}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.verifyMinutes").value(30))
        .andExpect(jsonPath("$.maxAutoRuns").value(5))
        .andExpect(jsonPath("$.windowHours").value(12));
  }
}

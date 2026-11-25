package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.sql.Timestamp;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.access.domain.SiteAccess;
import sn.ept.transdic1.access.repository.SiteAccessRepository;
import sn.ept.transdic1.alerting.domain.Alert;
import sn.ept.transdic1.alerting.domain.AlertState;
import sn.ept.transdic1.alerting.domain.AlertType;
import sn.ept.transdic1.alerting.domain.ResolutionType;
import sn.ept.transdic1.alerting.domain.Severity;
import sn.ept.transdic1.alerting.repository.AlertRepository;
import sn.ept.transdic1.alerting.service.IncidentGroupingService;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.EquipmentStatus;
import sn.ept.transdic1.inventory.domain.EquipmentType;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.SiteRepository;
import sn.ept.transdic1.security.domain.AppUser;
import sn.ept.transdic1.security.domain.Role;
import sn.ept.transdic1.security.repository.UserRepository;

/**
 * Post-mortem d'incident et indicateurs de service (ADR 0053) : reconstitués à partir de la base,
 * limités au périmètre de sites, rendus en JSON et en PDF.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PostMortemApiTests {
  private static final String PASSWORD = "Mot-de-passe-test-123";

  @Autowired private MockMvc mockMvc;
  @Autowired private IncidentGroupingService grouping;
  @Autowired private AlertRepository alerts;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private UserRepository users;
  @Autowired private SiteAccessRepository access;
  @Autowired private PasswordEncoder encoder;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private jakarta.persistence.EntityManager em;

  private final String suffix = UUID.randomUUID().toString().substring(0, 8);
  private final Instant opened =
      Instant.now().minus(Duration.ofHours(2)).truncatedTo(ChronoUnit.SECONDS);
  private Site siteA;
  private Site siteB;
  private UUID incident;
  private AppUser operator;

  @BeforeEach
  void data() {
    jdbc.update("DELETE FROM incident_alert");
    jdbc.update("DELETE FROM incident");
    siteA = sites.saveAndFlush(new Site("PM-A-" + suffix, "Site post-mortem"));
    siteB = sites.saveAndFlush(new Site("PM-B-" + suffix, "Autre site"));
    operator =
        users.saveAndFlush(
            new AppUser(
                "pm-noc-" + suffix,
                "pm-noc-" + suffix + "@example.test",
                encoder.encode(PASSWORD),
                Role.NOC_OPERATOR));
    Equipment router = equipment("Routeur de coeur", 1);
    Equipment server = equipment("Serveur ENT", 2);
    em.flush();

    Alert first = alert(router, Severity.CRITICAL, "Routeur injoignable", opened);
    first.setTakenAt(opened.plus(Duration.ofMinutes(6)));
    first.setState(AlertState.RESOLVED);
    first.setResolvedAt(opened.plus(Duration.ofMinutes(40)));
    first.setResolutionType(ResolutionType.MANUAL);
    Alert second = alert(server, Severity.WARNING, "Routeur injoignable", opened.plusSeconds(30));
    second.setState(AlertState.RESOLVED);
    second.setResolvedAt(opened.plus(Duration.ofMinutes(41)));
    second.setResolutionType(ResolutionType.AUTOMATIC);
    alerts.saveAndFlush(first);
    alerts.saveAndFlush(second);
    incident = grouping.attach(first.getId(), opened, true).orElseThrow().incidentId();
    grouping.attach(second.getId(), opened.plusSeconds(30), true);
    em.flush();
    jdbc.update(
        "UPDATE incident SET state = 'RESOLVED', resolved_at = ? WHERE id = ?",
        Timestamp.from(opened.plus(Duration.ofMinutes(41))),
        incident);

    // Prise en charge, une notification envoyée, une en échec, et la cause confirmée.
    event(first.getId(), "TAKEN", "IN_PROGRESS", "Je regarde", opened.plus(Duration.ofMinutes(6)));
    event(
        first.getId(),
        "RESOLVED",
        "RESOLVED",
        "Câble remplacé",
        opened.plus(Duration.ofMinutes(40)));
    notification(first.getId(), "EMAIL", "SENT", opened.plusSeconds(40));
    notification(first.getId(), "TEAMS", "FAILED", opened.plusSeconds(45));
    jdbc.update(
        "INSERT INTO cause_feedback(id, alert_id, source, strength, chosen, confirmed_at,"
            + " confirmed_by) VALUES (?, ?, 'EVENEMENT_RECENT', 0.9, true, ?, ?)",
        UUID.randomUUID(),
        first.getId(),
        Timestamp.from(opened.plus(Duration.ofMinutes(42))),
        operator.getUsername());
  }

  private Equipment equipment(String name, int n) {
    return equipments.saveAndFlush(
        new Equipment(
            "PM-" + UUID.randomUUID(),
            name,
            "10.96." + (Math.floorMod(suffix.hashCode(), 250) + 1) + "." + n,
            EquipmentType.ROUTER,
            EquipmentStatus.UP,
            siteA));
  }

  private Alert alert(Equipment on, Severity severity, String message, Instant at) {
    return alerts.saveAndFlush(
        new Alert(
            on,
            "pm:" + UUID.randomUUID(),
            AlertType.ANOMALY,
            AlertState.OPEN,
            severity,
            message,
            at));
  }

  private void event(UUID alertId, String type, String state, String comment, Instant at) {
    jdbc.update(
        "INSERT INTO alert_event(id, alert_id, author_id, type, new_state, comment, occurred_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?)",
        UUID.randomUUID(),
        alertId,
        operator.getId(),
        type,
        state,
        comment,
        Timestamp.from(at));
  }

  private void notification(UUID alertId, String channel, String state, Instant at) {
    jdbc.update(
        "INSERT INTO notification(id, user_id, alert_id, channel, destination_address, subject,"
            + " state, created_at, sent_at, kind) VALUES (?, ?, ?, ?, 'env:CANAL', 'Alerte', ?, ?,"
            + " ?, 'ALERT_OPENED')",
        UUID.randomUUID(),
        operator.getId(),
        alertId,
        channel,
        state,
        Timestamp.from(at),
        "SENT".equals(state) ? Timestamp.from(at) : null);
  }

  @Test
  @DisplayName(
      "Le post-mortem donne les durées, la chronologie, l'impact, la cause et les constats")
  void postMortem() throws Exception {
    String body =
        mockMvc
            .perform(
                get("/api/v1/incidents/" + incident + "/post-mortem")
                    .with(user("lecteur").roles("VIEWER")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.incident.siteName").value("Site post-mortem"))
            .andExpect(jsonPath("$.durations.minutesToAcknowledge").value(6.0))
            .andExpect(jsonPath("$.durations.minutesToResolve").value(41.0))
            .andExpect(jsonPath("$.durations.acknowledgeTargetMinutes").value(15))
            .andExpect(jsonPath("$.root.equipmentName").value("Routeur de coeur"))
            .andExpect(jsonPath("$.alerts.length()").value(2))
            .andExpect(jsonPath("$.impact.equipmentCount").value(2))
            .andExpect(jsonPath("$.impact.notificationsSent").value(1))
            .andExpect(jsonPath("$.impact.notificationsFailed").value(1))
            .andExpect(jsonPath("$.impact.notificationsAvoided").value(1))
            .andExpect(jsonPath("$.cause.confirmed").value(true))
            .andExpect(jsonPath("$.cause.label").value("Événement réseau (trap ou syslog)"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    List<String> kinds = JsonPath.read(body, "$.timeline[*].kind");
    assertThat(kinds.get(0)).isEqualTo("INCIDENT_OPENED");
    assertThat(kinds)
        .contains(
            "ALERT_OPENED",
            "NOTIFIED",
            "TAKEN",
            "RESOLVED",
            "INCIDENT_RESOLVED",
            "CAUSE_CONFIRMED");
    // Ordre chronologique : la cause confirmée après la fin de l'incident vient en dernier.
    List<String> times = JsonPath.read(body, "$.timeline[*].at");
    assertThat(times).isSorted();
    assertThat(kinds).endsWith("INCIDENT_RESOLVED", "CAUSE_CONFIRMED");
    List<String> findings = JsonPath.read(body, "$.findings[*].text");
    assertThat(findings)
        .contains(
            "Prise en charge en 6 min, dans l'objectif de 15 min.",
            "1 notification en échec (Teams).",
            "Cause confirmée par un opérateur.");
    List<String> responders = JsonPath.read(body, "$.responders");
    assertThat(responders).containsExactly(operator.getUsername());
  }

  @Test
  @DisplayName("Le même post-mortem en PDF")
  void pdf() throws Exception {
    byte[] pdf =
        mockMvc
            .perform(
                get("/api/v1/incidents/" + incident + "/post-mortem/pdf")
                    .with(user("lecteur").roles("VIEWER")))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_PDF))
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
    assertThat(new String(pdf, 0, 5, java.nio.charset.StandardCharsets.US_ASCII))
        .isEqualTo("%PDF-");
    assertThat(pdf.length).isGreaterThan(2000);
  }

  @Test
  @DisplayName("Indicateurs de service du site : MTTA, MTTR et équipements les plus touchés")
  void serviceKpis() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/indicateurs/service")
                .with(user("lecteur").roles("VIEWER"))
                .param("from", opened.minus(Duration.ofHours(1)).toString())
                .param("to", Instant.now().toString())
                .param("siteId", siteA.getId().toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.overall.alerts").value(2))
        .andExpect(jsonPath("$.overall.acknowledged").value(1))
        .andExpect(jsonPath("$.overall.mttaMinutes").value(6.0))
        .andExpect(jsonPath("$.overall.resolved").value(2))
        .andExpect(jsonPath("$.byOperator[0].username").value(operator.getUsername()))
        .andExpect(jsonPath("$.bySeverity[0].key").value("CRITICAL"))
        .andExpect(jsonPath("$.equipments.length()").value(2));
    mockMvc
        .perform(
            get("/api/v1/indicateurs/service")
                .with(user("lecteur").roles("VIEWER"))
                .param("from", Instant.now().toString())
                .param("to", opened.toString()))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("Un opérateur limité à un autre site ne voit pas le post-mortem")
  void scope() throws Exception {
    access.saveAndFlush(new SiteAccess(operator, siteB, Instant.now()));
    em.flush();
    String token = login(operator.getUsername());
    mockMvc
        .perform(
            get("/api/v1/incidents/" + incident + "/post-mortem")
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(
            get("/api/v1/incidents/" + UUID.randomUUID() + "/post-mortem")
                .with(user("lecteur").roles("VIEWER")))
        .andExpect(status().isNotFound());
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
}

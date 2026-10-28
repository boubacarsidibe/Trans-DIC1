package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
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
import sn.ept.transdic1.alerting.domain.Alert;
import sn.ept.transdic1.alerting.domain.AlertState;
import sn.ept.transdic1.alerting.domain.AlertType;
import sn.ept.transdic1.alerting.domain.Severity;
import sn.ept.transdic1.alerting.repository.AlertRepository;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.EquipmentStatus;
import sn.ept.transdic1.inventory.domain.EquipmentType;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.SiteRepository;
import sn.ept.transdic1.security.domain.AppUser;
import sn.ept.transdic1.security.domain.Role;
import sn.ept.transdic1.security.repository.UserRepository;

/** API du cycle de vie des alertes : droits par rôle, transitions autorisées, historique, audit. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AlertApiTests {
  private static final String PASSWORD = "Mot-de-passe-test-123";

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private PasswordEncoder encoder;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private AlertRepository alerts;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private jakarta.persistence.EntityManager em;

  private Equipment equipment;
  private Alert alert;
  private AppUser nocUser;
  private AppUser viewerUser;
  private AppUser adminUser;
  private String admin;
  private String noc;
  private String viewer;

  @BeforeEach
  void data() throws Exception {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    adminUser =
        users.save(
            new AppUser(
                "adm-" + suffix,
                "adm-" + suffix + "@example.test",
                encoder.encode(PASSWORD),
                Role.ADMIN));
    nocUser =
        users.save(
            new AppUser(
                "noc-" + suffix,
                "noc-" + suffix + "@example.test",
                encoder.encode(PASSWORD),
                Role.NOC_OPERATOR));
    viewerUser =
        users.save(
            new AppUser(
                "vwr-" + suffix,
                "vwr-" + suffix + "@example.test",
                encoder.encode(PASSWORD),
                Role.VIEWER));
    admin = login(adminUser.getUsername());
    noc = login(nocUser.getUsername());
    viewer = login(viewerUser.getUsername());

    String unique = UUID.randomUUID().toString();
    Site site = sites.save(new Site("ALA-" + suffix, "Site API alertes"));
    equipment =
        equipments.save(
            new Equipment(
                "ALA-EQ-" + unique,
                "Routeur API",
                "10.242."
                    + (Math.floorMod(unique.hashCode(), 250) + 1)
                    + "."
                    + (Math.floorMod(unique.substring(9).hashCode(), 250) + 1),
                EquipmentType.ROUTER,
                EquipmentStatus.UP,
                site));
    alert = newAlert(AlertState.OPEN);
  }

  private Alert newAlert(AlertState state) {
    var created =
        new Alert(
            equipment,
            "api-" + UUID.randomUUID(),
            AlertType.COLLECTION,
            state,
            Severity.CRITICAL,
            "Équipement injoignable",
            Instant.now());
    if (state == AlertState.RESOLVED) {
      created.setResolvedAt(Instant.now());
      created.setResolutionType(sn.ept.transdic1.alerting.domain.ResolutionType.AUTOMATIC);
    }
    return alerts.saveAndFlush(created);
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

  private ResultActions act(String method, String jwt, UUID id, String action, String body)
      throws Exception {
    String url = "/api/v1/alertes/" + id + (action == null ? "" : "/" + action);
    var request =
        switch (method) {
          case "POST" -> post(url);
          case "DELETE" -> delete(url);
          default -> get(url);
        };
    if (jwt != null) {
      request = request.header("Authorization", "Bearer " + jwt);
    }
    if (body != null) {
      request = request.contentType(MediaType.APPLICATION_JSON).content(body);
    }
    return mockMvc.perform(request);
  }

  private int activeAssignments() {
    em.flush();
    return jdbc.queryForObject(
        "SELECT count(*) FROM alert_assignment WHERE alert_id = ? AND ended_at IS NULL",
        Integer.class,
        alert.getId());
  }

  private int auditCount(String action) {
    em.flush();
    return jdbc.queryForObject(
        "SELECT count(*) FROM audit_entry WHERE action = ? AND target_id = ?",
        Integer.class,
        action,
        alert.getId());
  }

  @Test
  void everyRoleCanReadButAnonymousCannot() throws Exception {
    for (String jwt : new String[] {admin, noc, viewer}) {
      act("GET", jwt, alert.getId(), null, null)
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.alert.state").value("OPEN"))
          .andExpect(jsonPath("$.alert.equipmentName").value("Routeur API"));
    }
    act("GET", null, alert.getId(), null, null).andExpect(status().isUnauthorized());
    mockMvc.perform(get("/api/v1/alertes")).andExpect(status().isUnauthorized());
  }

  @Test
  void aViewerCannotChangeAnything() throws Exception {
    act("POST", viewer, alert.getId(), "prise-en-charge", null).andExpect(status().isForbidden());
    act("POST", viewer, alert.getId(), "resolution", null).andExpect(status().isForbidden());
    act("POST", viewer, alert.getId(), "archivage", null).andExpect(status().isForbidden());
    act("DELETE", viewer, alert.getId(), null, null).andExpect(status().isForbidden());
  }

  @Test
  void anOperatorTakesAnOpenAlertAndItAppearsInTheHistoryAndTheAudit() throws Exception {
    act("POST", noc, alert.getId(), "prise-en-charge", "{\"comment\":\"Je regarde\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("IN_PROGRESS"))
        .andExpect(jsonPath("$.assignee").value(nocUser.getUsername()))
        .andExpect(jsonPath("$.takenAt").isNotEmpty());

    act("GET", viewer, alert.getId(), null, null)
        .andExpect(jsonPath("$.events.length()").value(1))
        .andExpect(jsonPath("$.events[0].type").value("TAKEN"))
        .andExpect(jsonPath("$.events[0].previousState").value("OPEN"))
        .andExpect(jsonPath("$.events[0].newState").value("IN_PROGRESS"))
        .andExpect(jsonPath("$.events[0].author").value(nocUser.getUsername()))
        .andExpect(jsonPath("$.events[0].comment").value("Je regarde"));
    assertThat(auditCount("ALERT_TAKEN")).isEqualTo(1);
    // Une alerte déjà prise en charge ne peut pas l'être une seconde fois.
    act("POST", noc, alert.getId(), "prise-en-charge", null).andExpect(status().isConflict());
  }

  @Test
  void onlyAnAdministratorAssignsAndOnlyToAnActiveOperator() throws Exception {
    String toNoc = "{\"userId\":\"" + nocUser.getId() + "\"}";
    act("POST", noc, alert.getId(), "assignation", toNoc).andExpect(status().isForbidden());
    act("POST", admin, alert.getId(), "assignation", "{\"userId\":\"" + viewerUser.getId() + "\"}")
        .andExpect(status().isBadRequest());
    act("POST", admin, alert.getId(), "assignation", "{\"userId\":\"" + UUID.randomUUID() + "\"}")
        .andExpect(status().isBadRequest());

    act("POST", admin, alert.getId(), "assignation", toNoc)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("IN_PROGRESS"))
        .andExpect(jsonPath("$.assignee").value(nocUser.getUsername()));
  }

  @Test
  void reassigningReleasesThePreviousAssigneeAndKeepsASingleActiveAssignment() throws Exception {
    act("POST", noc, alert.getId(), "prise-en-charge", null).andExpect(status().isOk());

    act("POST", admin, alert.getId(), "assignation", "{\"userId\":\"" + adminUser.getId() + "\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.assignee").value(adminUser.getUsername()));

    act("GET", admin, alert.getId(), null, null)
        .andExpect(
            jsonPath("$.events[*].type")
                .value(org.hamcrest.Matchers.contains("TAKEN", "UNASSIGNED", "ASSIGNED")));
    int active = activeAssignments();
    assertThat(active).isEqualTo(1);
  }

  @Test
  void manualResolutionClosesTheAssignmentAndCannotBeRepeated() throws Exception {
    act("POST", noc, alert.getId(), "prise-en-charge", null).andExpect(status().isOk());

    act("POST", noc, alert.getId(), "resolution", "{\"comment\":\"Câble remplacé\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("RESOLVED"))
        .andExpect(jsonPath("$.resolvedAt").isNotEmpty())
        .andExpect(jsonPath("$.assignee").doesNotExist());

    Alert stored = alerts.findById(alert.getId()).orElseThrow();
    assertThat(stored.getResolutionType().name()).isEqualTo("MANUAL");
    int active = activeAssignments();
    assertThat(active).isZero();
    assertThat(auditCount("ALERT_RESOLVED")).isEqualTo(1);
    act("POST", noc, alert.getId(), "resolution", null).andExpect(status().isConflict());
  }

  @Test
  void anOpenAlertCanBeResolvedDirectly() throws Exception {
    act("POST", admin, alert.getId(), "resolution", null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("RESOLVED"));
  }

  @Test
  void archivingNeedsAResolvedAlertAndAnAdministrator() throws Exception {
    act("POST", admin, alert.getId(), "archivage", null).andExpect(status().isConflict());
    act("POST", noc, alert.getId(), "resolution", null).andExpect(status().isOk());
    act("POST", noc, alert.getId(), "archivage", null).andExpect(status().isForbidden());

    act("POST", admin, alert.getId(), "archivage", null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("ARCHIVED"));
    // Une alerte archivée ne peut plus être résolue ni prise en charge.
    act("POST", noc, alert.getId(), "resolution", null).andExpect(status().isConflict());
    act("POST", noc, alert.getId(), "prise-en-charge", null).andExpect(status().isConflict());
  }

  @Test
  void deletionIsLogicalAdminOnlyAndHidesTheAlert() throws Exception {
    act("DELETE", noc, alert.getId(), null, null).andExpect(status().isForbidden());

    act("DELETE", admin, alert.getId(), null, null).andExpect(status().isNoContent());

    act("GET", admin, alert.getId(), null, null).andExpect(status().isNotFound());
    act("DELETE", admin, alert.getId(), null, null).andExpect(status().isNotFound());
    assertThat(alerts.findById(alert.getId())).isPresent();
    assertThat(alerts.findById(alert.getId()).orElseThrow().getDeletedAt()).isNotNull();
    assertThat(auditCount("ALERT_DELETED")).isEqualTo(1);
    mockMvc
        .perform(
            get("/api/v1/alertes")
                .param("equipmentId", equipment.getId().toString())
                .header("Authorization", "Bearer " + viewer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(0));
  }

  @Test
  void theListFiltersByStateAndEquipmentAndPaginates() throws Exception {
    newAlert(AlertState.RESOLVED);
    newAlert(AlertState.ARCHIVED);

    mockMvc
        .perform(
            get("/api/v1/alertes")
                .param("equipmentId", equipment.getId().toString())
                .header("Authorization", "Bearer " + viewer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page.totalElements").value(3));
    mockMvc
        .perform(
            get("/api/v1/alertes")
                .param("equipmentId", equipment.getId().toString())
                .param("state", "RESOLVED")
                .header("Authorization", "Bearer " + viewer))
        .andExpect(jsonPath("$.page.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].state").value("RESOLVED"));
    mockMvc
        .perform(
            get("/api/v1/alertes")
                .param("equipmentId", equipment.getId().toString())
                .param("size", "2")
                .header("Authorization", "Bearer " + viewer))
        .andExpect(jsonPath("$.content.length()").value(2))
        .andExpect(jsonPath("$.page.totalPages").value(2));
  }

  @Test
  void unknownAlertsAreNotFound() throws Exception {
    act("GET", admin, UUID.randomUUID(), null, null).andExpect(status().isNotFound());
    act("POST", noc, UUID.randomUUID(), "prise-en-charge", null).andExpect(status().isNotFound());
  }
}

package sn.ept.transdic1;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
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
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.access.domain.SiteAccess;
import sn.ept.transdic1.access.repository.SiteAccessRepository;
import sn.ept.transdic1.alerting.domain.Alert;
import sn.ept.transdic1.alerting.domain.AlertState;
import sn.ept.transdic1.alerting.domain.AlertType;
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
 * API des incidents (M10, ADR 0039) : lecture seule, ouverte à tous les rôles, limitée au périmètre
 * de sites (ADR 0018).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class IncidentApiTests {
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
  private Site siteA;
  private Site siteB;
  private UUID incidentA;

  @BeforeEach
  void data() {
    // Les incidents des autres classes de test fausseraient les comptes de la liste.
    jdbc.update("DELETE FROM incident_alert");
    jdbc.update("DELETE FROM incident");
    siteA = sites.saveAndFlush(new Site("IAPI-A-" + suffix, "Site A"));
    siteB = sites.saveAndFlush(new Site("IAPI-B-" + suffix, "Site B"));
    Equipment router = equipment("Routeur de coeur", siteA, 1);
    Equipment server = equipment("Serveur applicatif", siteA, 2);
    em.flush();
    Alert first = alert(router, Severity.WARNING, "Processeur inhabituel");
    Alert second = alert(server, Severity.CRITICAL, "Processeur inhabituel");
    incidentA = grouping.attach(first.getId(), Instant.now(), true).orElseThrow().incidentId();
    grouping.attach(second.getId(), Instant.now(), true);
    em.flush();
  }

  private Equipment equipment(String name, Site where, int suffixe) {
    return equipments.saveAndFlush(
        new Equipment(
            "IAPI-" + UUID.randomUUID(),
            name,
            "10.99." + (Math.floorMod(suffix.hashCode(), 250) + 1) + "." + suffixe,
            EquipmentType.ROUTER,
            EquipmentStatus.UP,
            where));
  }

  private Alert alert(Equipment on, Severity severity, String message) {
    return alerts.saveAndFlush(
        new Alert(
            on,
            "iapi:" + UUID.randomUUID(),
            AlertType.ANOMALY,
            AlertState.OPEN,
            severity,
            message,
            Instant.now()));
  }

  @Test
  @WithMockUser(roles = "VIEWER")
  @DisplayName("La liste donne l'incident, ses alertes et les équipements touchés")
  void thelistGivesTheincidentItsAlertsAndTheEquipmentsTouched() throws Exception {
    mockMvc
        .perform(get("/api/v1/incidents"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(jsonPath("$.content[0].title").value("Processeur inhabituel"))
        // La sévérité de l'incident est la plus haute de ses alertes.
        .andExpect(jsonPath("$.content[0].severity").value("CRITICAL"))
        .andExpect(jsonPath("$.content[0].state").value("OPEN"))
        .andExpect(jsonPath("$.content[0].alertCount").value(2))
        .andExpect(jsonPath("$.content[0].equipmentCount").value(2))
        .andExpect(jsonPath("$.content[0].siteName").value("Site A"))
        .andExpect(jsonPath("$.content[0].resolvedAt").doesNotExist());
  }

  @Test
  @WithMockUser(roles = "VIEWER")
  @DisplayName("Le détail dit pourquoi chaque alerte a été rapprochée, et qui a notifié")
  void thedetailSaysWhyEachAlertWasGroupedAndWhonotified() throws Exception {
    mockMvc
        .perform(get("/api/v1/incidents/" + incidentA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.incident.id").value(incidentA.toString()))
        .andExpect(jsonPath("$.alerts.length()").value(2))
        .andExpect(jsonPath("$.alerts[0].reason").value("Première alerte de l'incident"))
        .andExpect(jsonPath("$.alerts[0].announces").value(true))
        .andExpect(jsonPath("$.alerts[0].equipmentName").value("Routeur de coeur"))
        .andExpect(
            jsonPath("$.alerts[1].reason")
                .value("Même site et alerte semblable : Routeur de coeur (100 % de mots communs)"))
        // Elle a notifié aussi : l'incident est passé de avertissement à critique.
        .andExpect(jsonPath("$.alerts[1].announces").value(true));
  }

  @Test
  @WithMockUser(roles = "VIEWER")
  @DisplayName("Le filtre par état fonctionne, et un incident inconnu est introuvable")
  void thestateFilterWorksAndAnunknownincidentIsNotFound() throws Exception {
    mockMvc
        .perform(get("/api/v1/incidents").param("state", "RESOLVED"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(0));
    mockMvc
        .perform(get("/api/v1/incidents").param("state", "OPEN"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(1));
    mockMvc.perform(get("/api/v1/incidents/" + UUID.randomUUID())).andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("Un opérateur limité au site B ne voit pas l'incident du site A")
  void anoperatorLimitedToSiteBdoesNotSeeTheincidentOfSiteA() throws Exception {
    AppUser limited =
        users.saveAndFlush(
            new AppUser(
                "iapi-noc-" + suffix,
                "iapi-noc-" + suffix + "@example.test",
                encoder.encode(PASSWORD),
                Role.NOC_OPERATOR));
    access.saveAndFlush(new SiteAccess(limited, siteB, Instant.now()));
    em.flush();
    String token = login("iapi-noc-" + suffix);

    mockMvc
        .perform(get("/api/v1/incidents").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(0));
    // Introuvable, et non « interdit » : on ne révèle pas qu'il existe.
    mockMvc
        .perform(get("/api/v1/incidents/" + incidentA).header("Authorization", "Bearer " + token))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("L'authentification est requise")
  void authenticationIsRequired() throws Exception {
    mockMvc.perform(get("/api/v1/incidents")).andExpect(status().isUnauthorized());
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

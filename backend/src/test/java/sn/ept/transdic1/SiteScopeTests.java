package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.hamcrest.Matchers;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.access.domain.SiteAccess;
import sn.ept.transdic1.access.repository.SiteAccessRepository;
import sn.ept.transdic1.alerting.domain.Alert;
import sn.ept.transdic1.alerting.domain.AlertEventType;
import sn.ept.transdic1.alerting.domain.AlertState;
import sn.ept.transdic1.alerting.domain.AlertType;
import sn.ept.transdic1.alerting.domain.Maintenance;
import sn.ept.transdic1.alerting.domain.MaintenanceState;
import sn.ept.transdic1.alerting.domain.Severity;
import sn.ept.transdic1.alerting.repository.AlertRepository;
import sn.ept.transdic1.alerting.repository.MaintenanceRepository;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.EquipmentStatus;
import sn.ept.transdic1.inventory.domain.EquipmentType;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.SiteRepository;
import sn.ept.transdic1.reporting.domain.NotificationChannel;
import sn.ept.transdic1.reporting.domain.NotificationPreference;
import sn.ept.transdic1.reporting.repository.NotificationPreferenceRepository;
import sn.ept.transdic1.reporting.service.NotificationQueueService;
import sn.ept.transdic1.security.domain.AppUser;
import sn.ept.transdic1.security.domain.Role;
import sn.ept.transdic1.security.repository.UserRepository;

/**
 * Périmètre d'accès par site (FEAT-01, ADR 0018) : un opérateur limité au site A ne voit, ne
 * modifie et n'est prévenu de rien sur le site B ; un administrateur et un compte sans site voient
 * tout.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SiteScopeTests {
  private static final String PASSWORD = "Mot-de-passe-test-123";

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private PasswordEncoder encoder;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private AlertRepository alerts;
  @Autowired private MaintenanceRepository maintenances;
  @Autowired private SiteAccessRepository access;
  @Autowired private NotificationPreferenceRepository preferences;
  @Autowired private NotificationQueueService queue;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private jakarta.persistence.EntityManager em;

  private final String suffix = UUID.randomUUID().toString().substring(0, 8);
  private Site siteA;
  private Site siteB;
  private Equipment equipmentA;
  private Equipment equipmentB;
  private Alert alertA;
  private Alert alertB;
  private AppUser limited;
  private AppUser otherNoc;
  private AppUser adminUser;
  private String admin;
  private String noc;
  private String free;

  @BeforeEach
  void data() throws Exception {
    siteA = sites.save(new Site("SCA-" + suffix, "Site A " + suffix));
    siteB = sites.save(new Site("SCB-" + suffix, "Site B " + suffix));
    equipmentA = equipments.save(equipment("A", siteA, 11));
    equipmentB = equipments.save(equipment("B", siteB, 12));
    alertA = alerts.save(alert(equipmentA));
    alertB = alerts.save(alert(equipmentB));

    adminUser = user("sc-adm-", Role.ADMIN);
    limited = user("sc-noc-", Role.NOC_OPERATOR);
    otherNoc = user("sc-nocb-", Role.NOC_OPERATOR);
    user("sc-free-", Role.VIEWER);
    access.save(new SiteAccess(limited, siteA, Instant.now()));
    access.save(new SiteAccess(otherNoc, siteB, Instant.now()));
    access.flush();

    var planner = adminUser;
    var onA =
        new Maintenance(planner, inHours(1), inHours(2), "Travaux A", MaintenanceState.PLANNED);
    onA.setSite(siteA);
    var onB =
        new Maintenance(planner, inHours(1), inHours(2), "Travaux B", MaintenanceState.PLANNED);
    onB.setEquipment(equipmentB);
    maintenances.saveAndFlush(onA);
    maintenances.saveAndFlush(onB);

    admin = login("sc-adm-" + suffix);
    noc = login("sc-noc-" + suffix);
    free = login("sc-free-" + suffix);
  }

  @Test
  void lInventaireNeMontreQueLesSitesAttribues() throws Exception {
    call(get("/api/v1/equipements?size=500"), noc)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[*].id", Matchers.contains(equipmentA.getId().toString())));
    call(get("/api/v1/equipements/" + equipmentB.getId()), noc).andExpect(status().isNotFound());
    call(get("/api/v1/sites?size=500"), noc)
        .andExpect(jsonPath("$.content[*].id", Matchers.contains(siteA.getId().toString())));
    call(get("/api/v1/sites/" + siteB.getId()), noc).andExpect(status().isNotFound());

    // Créer ou déplacer vers un site hors périmètre est refusé ; créer un site aussi.
    call(post("/api/v1/equipements").content(equipmentJson("C", siteB, 13)), noc)
        .andExpect(status().isForbidden());
    call(
            put("/api/v1/equipements/" + equipmentA.getId()).content(equipmentJson("A", siteB, 11)),
            noc)
        .andExpect(status().isForbidden());
    call(post("/api/v1/equipements").content(equipmentJson("D", siteA, 14)), noc)
        .andExpect(status().isCreated());
    call(post("/api/v1/sites").content("{\"code\":\"SCX-" + suffix + "\",\"name\":\"X\"}"), noc)
        .andExpect(status().isForbidden());

    // Import CSV : un site hors périmètre est traité comme inconnu.
    String csv =
        "code_inventaire;nom;adresse;type;statut;site\n"
            + "SCI-"
            + suffix
            + ";Import;192.0.2.77;SWITCH;UP;"
            + siteB.getCode()
            + "\n";
    mockMvc
        .perform(
            post("/api/v1/equipements/import/apercu")
                .header("Authorization", "Bearer " + noc)
                .contentType("text/csv")
                .content(csv))
        .andExpect(jsonPath("$.valid.length()").value(0))
        .andExpect(jsonPath("$.errors.length()").value(1));
  }

  @Test
  void lesAlertesEtMaintenancesHorsPerimetreSontInvisibles() throws Exception {
    call(get("/api/v1/alertes?size=500"), noc)
        .andExpect(jsonPath("$.content[*].id", Matchers.contains(alertA.getId().toString())));
    call(get("/api/v1/alertes/" + alertB.getId()), noc).andExpect(status().isNotFound());
    call(post("/api/v1/alertes/" + alertB.getId() + "/prise-en-charge").content("{}"), noc)
        .andExpect(status().isNotFound());

    call(get("/api/v1/maintenances?size=500"), noc)
        .andExpect(jsonPath("$.content[*].reason", Matchers.contains("Travaux A")));
    String onB =
        "{\"equipmentId\":\""
            + equipmentB.getId()
            + "\",\"startAt\":\""
            + inHours(3)
            + "\",\"endAt\":\""
            + inHours(4)
            + "\",\"reason\":\"Hors périmètre\"}";
    call(post("/api/v1/maintenances").content(onB), noc).andExpect(status().isForbidden());

    // Un opérateur du site B ne peut pas être assigné à une alerte du site A.
    call(
            post("/api/v1/alertes/" + alertA.getId() + "/assignes")
                .content("{\"userId\":\"" + otherNoc.getId() + "\"}"),
            admin)
        .andExpect(status().isBadRequest());
    call(
            post("/api/v1/alertes/" + alertA.getId() + "/assignes")
                .content("{\"userId\":\"" + limited.getId() + "\"}"),
            admin)
        .andExpect(status().isOk());
  }

  @Test
  void tableauDeBordRapportsMesuresEtTopologieSuiventLePerimetre() throws Exception {
    call(get("/api/v1/vue-ensemble"), noc)
        .andExpect(jsonPath("$.equipments.total").value(1))
        .andExpect(jsonPath("$.alerts.active").value(1));

    String period = "from=" + inHours(-24) + "&to=" + inHours(0);
    call(get("/api/v1/rapports/disponibilite?" + period), noc)
        .andExpect(
            jsonPath("$.rows[*].equipmentId", Matchers.contains(equipmentA.getId().toString())));
    call(get("/api/v1/rapports/disponibilite?" + period + "&siteId=" + siteB.getId()), noc)
        .andExpect(status().isNotFound());
    call(get("/api/v1/rapports/incidents?" + period), noc).andExpect(jsonPath("$.total").value(1));
    call(get("/api/v1/rapports/sla?" + period), noc)
        .andExpect(jsonPath("$.sites[*].siteId", Matchers.contains(siteA.getId().toString())));

    call(get("/api/v1/equipements/" + equipmentB.getId() + "/metriques/indicateurs"), noc)
        .andExpect(status().isNotFound());
    call(get("/api/v1/topologie"), noc)
        .andExpect(jsonPath("$.nodes[*].id", Matchers.contains(equipmentA.getId().toString())));
  }

  @Test
  void administrateurEtCompteSansSiteVoientTout() throws Exception {
    for (String token : List.of(admin, free)) {
      call(get("/api/v1/equipements?size=500"), token)
          .andExpect(
              jsonPath(
                  "$.content[*].id",
                  Matchers.hasItems(equipmentA.getId().toString(), equipmentB.getId().toString())));
      call(get("/api/v1/alertes/" + alertB.getId()), token).andExpect(status().isOk());
    }
  }

  @Test
  void lAdministrateurAttribueLesSitesEtLeChangementEstImmediatEtTrace() throws Exception {
    call(get("/api/v1/perimetres"), noc).andExpect(status().isForbidden());
    call(get("/api/v1/perimetres"), admin)
        .andExpect(
            jsonPath("$[?(@.userId == '" + limited.getId() + "')].siteIds[0]")
                .value(siteA.getId().toString()));

    // Ajout du site B : visible tout de suite, sans nouvelle connexion.
    String both = "{\"siteIds\":[\"" + siteA.getId() + "\",\"" + siteB.getId() + "\"]}";
    call(put("/api/v1/perimetres/" + limited.getId()).content(both), admin)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.siteIds.length()").value(2));
    call(get("/api/v1/alertes/" + alertB.getId()), noc).andExpect(status().isOk());
    assertThat(
            jdbc.queryForObject(
                "SELECT details_sanitized FROM audit_entry WHERE action = 'USER_SCOPE_UPDATED'"
                    + " AND target_id = ? ORDER BY performed_at DESC LIMIT 1",
                String.class,
                limited.getId()))
        .contains(siteA.getCode(), siteB.getCode());

    // Liste vide : retour à tout le parc. Un administrateur ne se limite pas.
    call(put("/api/v1/perimetres/" + limited.getId()).content("{\"siteIds\":[]}"), admin)
        .andExpect(jsonPath("$.siteIds.length()").value(0));
    call(
            put("/api/v1/perimetres/" + adminUser.getId())
                .content("{\"siteIds\":[\"" + siteA.getId() + "\"]}"),
            admin)
        .andExpect(status().isBadRequest());
  }

  @Test
  void personneNEstPrevenuDUneAlerteQuIlNePeutPasVoir() {
    preferences.saveAndFlush(
        new NotificationPreference(limited, NotificationChannel.EMAIL, Severity.INFO));
    queue.queueFor(UUID.randomUUID(), alertB.getId(), AlertEventType.OPENED);
    queue.queueFor(UUID.randomUUID(), alertA.getId(), AlertEventType.OPENED);
    em.flush();

    List<UUID> notified =
        jdbc.queryForList(
            "SELECT alert_id FROM notification WHERE user_id = ?", UUID.class, limited.getId());
    assertThat(notified).containsExactly(alertA.getId());
  }

  // ------------------------------------------------------------------ outils

  private ResultActions call(MockHttpServletRequestBuilder request, String token) throws Exception {
    return mockMvc.perform(
        request.header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON));
  }

  private Equipment equipment(String name, Site site, int host) {
    return new Equipment(
        "SC" + name + "-" + suffix,
        "Équipement " + name + " " + suffix,
        "198.51." + (Math.floorMod(suffix.hashCode(), 200) + 20) + "." + host,
        EquipmentType.ROUTER,
        EquipmentStatus.UP,
        site);
  }

  private String equipmentJson(String name, Site site, int host) {
    var e = equipment(name, site, host);
    return "{\"inventoryCode\":\""
        + e.getInventoryCode()
        + "\",\"name\":\""
        + e.getName()
        + "\",\"managementAddress\":\""
        + e.getManagementAddress()
        + "\",\"type\":\"ROUTER\",\"status\":\"UP\",\"siteId\":\""
        + site.getId()
        + "\"}";
  }

  private static Alert alert(Equipment equipment) {
    return new Alert(
        equipment,
        "scope-" + UUID.randomUUID(),
        AlertType.COLLECTION,
        AlertState.OPEN,
        Severity.CRITICAL,
        "Équipement injoignable",
        Instant.now().minus(1, ChronoUnit.HOURS));
  }

  private AppUser user(String prefix, Role role) {
    return users.saveAndFlush(
        new AppUser(
            prefix + suffix, prefix + suffix + "@example.test", encoder.encode(PASSWORD), role));
  }

  private static Instant inHours(long hours) {
    return Instant.now().plus(hours, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
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

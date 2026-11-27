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
import sn.ept.transdic1.alerting.domain.Alert;
import sn.ept.transdic1.alerting.domain.AlertRule;
import sn.ept.transdic1.alerting.domain.AlertState;
import sn.ept.transdic1.alerting.domain.AlertType;
import sn.ept.transdic1.alerting.domain.ComparisonOperator;
import sn.ept.transdic1.alerting.domain.Incident;
import sn.ept.transdic1.alerting.domain.IncidentAlert;
import sn.ept.transdic1.alerting.domain.Severity;
import sn.ept.transdic1.alerting.repository.AlertRepository;
import sn.ept.transdic1.alerting.repository.AlertRuleRepository;
import sn.ept.transdic1.alerting.repository.IncidentAlertRepository;
import sn.ept.transdic1.alerting.repository.IncidentRepository;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.EquipmentStatus;
import sn.ept.transdic1.inventory.domain.EquipmentType;
import sn.ept.transdic1.inventory.domain.MonitoredService;
import sn.ept.transdic1.inventory.domain.ServiceType;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.MonitoredServiceRepository;
import sn.ept.transdic1.inventory.repository.SiteRepository;
import sn.ept.transdic1.monitoring.domain.MeasurementQuality;
import sn.ept.transdic1.monitoring.domain.Metric;
import sn.ept.transdic1.monitoring.repository.MetricRepository;
import sn.ept.transdic1.security.domain.AppUser;
import sn.ept.transdic1.security.domain.Role;
import sn.ept.transdic1.security.repository.UserRepository;

/**
 * Cause racine (ADR 0056) sur des pannes construites en base, et saisie des dépendances entre
 * services : les quatre conclusions attendues par le CRI, plusieurs causes, périmètre par site,
 * incident, cycles refusés, import CSV. Transactionnel : rien ne reste en base.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class RootCauseApiTests {
  private static final String PASSWORD = "Mot-de-passe-test-123";
  private static final Instant T0 =
      Instant.now().truncatedTo(ChronoUnit.SECONDS).minus(Duration.ofMinutes(30));

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private PasswordEncoder encoder;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private MonitoredServiceRepository services;
  @Autowired private AlertRepository alerts;
  @Autowired private AlertRuleRepository rules;
  @Autowired private MetricRepository metrics;
  @Autowired private IncidentRepository incidents;
  @Autowired private IncidentAlertRepository incidentAlerts;
  @Autowired private SiteAccessRepository access;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private jakarta.persistence.EntityManager em;

  private Site campus;
  private Site datacenter;
  private String adminJwt;
  private String viewerJwt;
  private String limitedJwt;

  @BeforeEach
  void data() throws Exception {
    users.deleteAll();
    users.save(
        new AppUser("rc-admin", "rc-admin@example.test", encoder.encode(PASSWORD), Role.ADMIN));
    users.save(
        new AppUser("rc-viewer", "rc-viewer@example.test", encoder.encode(PASSWORD), Role.VIEWER));
    var limited =
        users.save(
            new AppUser(
                "rc-noc", "rc-noc@example.test", encoder.encode(PASSWORD), Role.NOC_OPERATOR));
    String unique = UUID.randomUUID().toString().substring(0, 6);
    campus = sites.save(new Site("RC-C-" + unique, "Campus"));
    datacenter = sites.save(new Site("RC-D-" + unique, "Salle cœur de réseau"));
    access.save(new SiteAccess(limited, campus, Instant.now()));
    em.flush();
    adminJwt = login("rc-admin");
    viewerJwt = login("rc-viewer");
    limitedJwt = login("rc-noc");
  }

  // --- Outils
  // -------------------------------------------------------------------------------------

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

  private Equipment equipment(String name, EquipmentType type, Site site) {
    String unique = UUID.randomUUID().toString();
    return equipments.saveAndFlush(
        new Equipment(
            "RC-" + unique.substring(0, 8),
            name,
            "198.51.100."
                + (Math.floorMod(unique.hashCode(), 250) + 1)
                + "-"
                + unique.substring(0, 4),
            type,
            EquipmentStatus.UP,
            site));
  }

  private MonitoredService service(Equipment on, String name, ServiceType type) {
    return services.saveAndFlush(new MonitoredService(on, name, type));
  }

  private Alert alert(Equipment on, AlertType type, String message, Instant at) {
    var alert =
        new Alert(
            on, "rc:" + UUID.randomUUID(), type, AlertState.OPEN, Severity.CRITICAL, message, at);
    return alerts.saveAndFlush(alert);
  }

  /** Alerte de seuil sur un service : la base exige une règle pour une alerte de seuil. */
  private Alert serviceAlert(MonitoredService service, String message, Instant at) {
    var rule =
        rules.saveAndFlush(
            new AlertRule(
                "Service " + service.getName() + " " + UUID.randomUUID().toString().substring(0, 6),
                "probe.up",
                ComparisonOperator.LESS_THAN,
                BigDecimal.ONE,
                Duration.ZERO,
                Severity.CRITICAL));
    var alert =
        new Alert(
            service.getEquipment(),
            "rc:" + UUID.randomUUID(),
            AlertType.THRESHOLD,
            AlertState.OPEN,
            Severity.CRITICAL,
            message,
            at);
    alert.setService(service);
    alert.setRule(rule);
    return alerts.saveAndFlush(alert);
  }

  private Alert diskAlert(Equipment on, Instant at) {
    var rule =
        rules.saveAndFlush(
            new AlertRule(
                "Disque plein " + on.getName() + " " + UUID.randomUUID().toString().substring(0, 6),
                "disk.usage",
                ComparisonOperator.GREATER_THAN,
                new BigDecimal("95"),
                Duration.ZERO,
                Severity.CRITICAL));
    var alert =
        new Alert(
            on,
            "rc:" + UUID.randomUUID(),
            AlertType.THRESHOLD,
            AlertState.OPEN,
            Severity.CRITICAL,
            "disk.usage = 99 % > 95 %",
            at);
    alert.setRule(rule);
    return alerts.saveAndFlush(alert);
  }

  private void link(Equipment child, Equipment upstream) {
    jdbc.update(
        "INSERT INTO equipment_link(id, equipment_id, upstream_id) VALUES (?, ?, ?)",
        UUID.randomUUID(),
        child.getId(),
        upstream.getId());
  }

  private void dependsOn(MonitoredService service, MonitoredService dependency) {
    jdbc.update(
        "INSERT INTO service_dependency(id, service_id, depends_on_id, created_at) VALUES (?, ?, ?, now())",
        UUID.randomUUID(),
        service.getId(),
        dependency.getId());
  }

  private void metric(Equipment on, String key, String value, Instant at) {
    metrics.saveAndFlush(
        new Metric(on, key, new BigDecimal(value), "state", MeasurementQuality.GOOD, at));
  }

  private ResultActions rootCause(String path, String jwt) throws Exception {
    return mockMvc.perform(
        get("/api/v1/" + path + "/cause-racine").header("Authorization", "Bearer " + jwt));
  }

  // --- Les conclusions attendues
  // ------------------------------------------------------------------

  @Test
  @DisplayName("L'ENT est injoignable parce que le routeur de cœur est hors service")
  void entBecauseOfTheCoreRouter() throws Exception {
    var router = equipment("rtr-coeur", EquipmentType.ROUTER, campus);
    var vm = equipment("vm-ent", EquipmentType.VIRTUAL_MACHINE, campus);
    link(vm, router);
    var ent = service(vm, "ENT", ServiceType.HTTPS);
    var db =
        service(
            equipment("vm-db", EquipmentType.VIRTUAL_MACHINE, campus),
            "PostgreSQL",
            ServiceType.TCP_PORT);
    dependsOn(ent, db);
    alert(router, AlertType.COLLECTION, "rtr-coeur ne répond plus", T0.minusSeconds(60));
    alert(vm, AlertType.COLLECTION, "vm-ent ne répond plus", T0.minusSeconds(30));
    var start = serviceAlert(ent, "ENT : HTTPS en échec", T0);

    rootCause("alertes/" + start.getId(), adminJwt)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.trouvee").value(true))
        .andExpect(
            jsonPath("$.conclusion")
                .value("ENT est injoignable parce que le routeur rtr-coeur est hors service"))
        .andExpect(jsonPath("$.causes[0].etapes[0].type").value("SERVICE"))
        .andExpect(jsonPath("$.causes[0].etapes[1].relation").value("tourne sur"))
        .andExpect(jsonPath("$.causes[0].etapes[2].relation").value("derrière"))
        .andExpect(jsonPath("$.causes[0].etapes[2].etat").value("hors service"))
        .andExpect(
            jsonPath("$.causes[0].etapes[2].preuves[0]")
                .value(org.hamcrest.Matchers.containsString("rtr-coeur ne répond plus")))
        .andExpect(jsonPath("$.causes.length()").value(1));
  }

  @Test
  @DisplayName("La VM est éteinte parce que son hôte est hors service")
  void vmBecauseOfItsHost() throws Exception {
    var host = equipment("proxmox-02", EquipmentType.SERVER, campus);
    var vm = equipment("vm-web-01", EquipmentType.VIRTUAL_MACHINE, campus);
    link(vm, host);
    alert(host, AlertType.COLLECTION, "Hôte proxmox-02 hors ligne", T0.minusSeconds(20));
    metric(vm, "hv.power", "0", T0.minusSeconds(10));
    var start = alert(vm, AlertType.COLLECTION, "VM vm-web-01 éteinte", T0);

    rootCause("alertes/" + start.getId(), viewerJwt)
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.conclusion")
                .value(
                    "La VM vm-web-01 est éteinte parce que son hôte proxmox-02 est hors service"))
        .andExpect(jsonPath("$.causes[0].etapes[1].relation").value("hébergée par"));
  }

  @Test
  @DisplayName("nginx est arrêté sur vm-web-01")
  void nginxStopped() throws Exception {
    var vm = equipment("vm-web-01", EquipmentType.VIRTUAL_MACHINE, campus);
    var nginx = service(vm, "nginx", ServiceType.AGENT_SERVICE);
    metric(vm, "service.up[nginx]", "0", T0.minusSeconds(5));
    var start = serviceAlert(nginx, "service.up[nginx] = 0", T0);

    rootCause("alertes/" + start.getId(), adminJwt)
        .andExpect(jsonPath("$.trouvee").value(false))
        .andExpect(jsonPath("$.conclusion").value("nginx est arrêté sur vm-web-01"))
        .andExpect(jsonPath("$.causes[0].etapes[0].etat").value("arrêté"));
  }

  @Test
  @DisplayName("La base ne répond plus : disque plein sur vm-db-01")
  void databaseBecauseOfAFullDisk() throws Exception {
    var vm = equipment("vm-db-01", EquipmentType.VIRTUAL_MACHINE, campus);
    var postgres = service(vm, "PostgreSQL", ServiceType.TCP_PORT);
    diskAlert(vm, T0.minus(Duration.ofMinutes(5)));
    var start = serviceAlert(postgres, "PostgreSQL : port 5432 fermé", T0);

    rootCause("alertes/" + start.getId(), adminJwt)
        .andExpect(
            jsonPath("$.conclusion").value("PostgreSQL ne répond plus : disque plein sur vm-db-01"))
        .andExpect(jsonPath("$.causes[0].source").value("TENDANCE_SATURATION"))
        .andExpect(jsonPath("$.causes[0].etapes[2].type").value("RESSOURCE"));
  }

  @Test
  @DisplayName(
      "Une panne d'un autre moment n'est pas une cause ; l'amont d'un équipement sain non plus")
  void onlySimultaneousFaultsCount() throws Exception {
    var router = equipment("rtr-coeur", EquipmentType.ROUTER, campus);
    var vm = equipment("vm-ent", EquipmentType.VIRTUAL_MACHINE, campus);
    link(vm, router);
    var ent = service(vm, "ENT", ServiceType.HTTPS);
    // Le routeur était tombé, mais il y a deux jours : résolu depuis.
    var old =
        alert(
            router, AlertType.COLLECTION, "rtr-coeur ne répond plus", T0.minus(Duration.ofDays(2)));
    old.setState(AlertState.RESOLVED);
    old.setResolvedAt(T0.minus(Duration.ofDays(2)).plusSeconds(600));
    old.setResolutionType(sn.ept.transdic1.alerting.domain.ResolutionType.AUTOMATIC);
    alerts.saveAndFlush(old);
    var start = serviceAlert(ent, "ENT : HTTPS en échec", T0);

    rootCause("alertes/" + start.getId(), adminJwt)
        .andExpect(jsonPath("$.trouvee").value(false))
        .andExpect(
            jsonPath("$.conclusion")
                .value(
                    "ENT ne répond plus sur vm-ent ; aucune panne plus profonde au même moment"));
  }

  @Test
  @DisplayName("Plusieurs causes simultanées sont toutes proposées, classées")
  void severalCausesAreRanked() throws Exception {
    var vm = equipment("vm-ent", EquipmentType.VIRTUAL_MACHINE, campus);
    var ent = service(vm, "ENT", ServiceType.HTTPS);
    var dbVm = equipment("vm-db-01", EquipmentType.VIRTUAL_MACHINE, campus);
    var db = service(dbVm, "PostgreSQL", ServiceType.TCP_PORT);
    var ldap =
        service(
            equipment("srv-ldap", EquipmentType.SERVER, campus),
            "slapd",
            ServiceType.AGENT_SERVICE);
    dependsOn(ent, db);
    dependsOn(ent, ldap);
    serviceAlert(db, "PostgreSQL : port 5432 fermé", T0.minusSeconds(40));
    diskAlert(dbVm, T0.minusSeconds(300));
    serviceAlert(ldap, "slapd arrêté", T0.minusSeconds(30));
    var start = serviceAlert(ent, "ENT : HTTPS en échec", T0);

    String body =
        rootCause("alertes/" + start.getId(), adminJwt)
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    List<String> conclusions = JsonPath.read(body, "$.causes[*].conclusion");
    assertThat(conclusions)
        .containsExactlyInAnyOrder(
            "ENT est en panne : slapd est arrêté sur srv-ldap",
            "ENT ne répond plus : disque plein sur vm-db-01");
    assertThat((String) JsonPath.read(body, "$.conclusion")).isEqualTo(conclusions.get(0));
  }

  @Test
  @DisplayName("Périmètre : un élément d'un autre site arrête la recherche sans être nommé")
  void outOfScopeNodesAreNeverNamed() throws Exception {
    var router = equipment("rtr-cœur-secret", EquipmentType.ROUTER, datacenter);
    var vm = equipment("vm-ent", EquipmentType.VIRTUAL_MACHINE, campus);
    link(vm, router);
    alert(router, AlertType.COLLECTION, "rtr ne répond plus", T0.minusSeconds(60));
    var start = alert(vm, AlertType.COLLECTION, "vm-ent ne répond plus", T0);

    String body =
        rootCause("alertes/" + start.getId(), limitedJwt)
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(body).doesNotContain("rtr-cœur-secret").doesNotContain(router.getId().toString());
    assertThat((String) JsonPath.read(body, "$.conclusion"))
        .isEqualTo("La VM vm-ent est en panne à cause d'un élément hors de votre périmètre.");
    assertThat((String) JsonPath.read(body, "$.causes[0].etapes[1].type"))
        .isEqualTo("HORS_PERIMETRE");

    var hidden = diskAlert(router, T0);
    rootCause("alertes/" + hidden.getId(), limitedJwt).andExpect(status().isNotFound());
    rootCause("alertes/" + UUID.randomUUID(), adminJwt).andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("Cause racine d'un incident : depuis son alerte racine")
  void incidentRootCause() throws Exception {
    var host = equipment("proxmox-02", EquipmentType.SERVER, campus);
    var vm = equipment("vm-web-01", EquipmentType.VIRTUAL_MACHINE, campus);
    link(vm, host);
    var hostAlert =
        alert(host, AlertType.COLLECTION, "Hôte proxmox-02 hors ligne", T0.minusSeconds(20));
    var vmAlert = alert(vm, AlertType.COLLECTION, "VM vm-web-01 injoignable", T0);
    vmAlert.correlateTo(hostAlert.getId(), "Amont injoignable : proxmox-02");
    alerts.saveAndFlush(vmAlert);
    var incident =
        incidents.saveAndFlush(
            new Incident(campus.getId(), "Panne de proxmox-02", Severity.CRITICAL, T0));
    incidentAlerts.saveAndFlush(
        new IncidentAlert(incident.getId(), vmAlert.getId(), T0, "même site", false));
    incidentAlerts.saveAndFlush(
        new IncidentAlert(incident.getId(), hostAlert.getId(), T0, "même site", true));

    rootCause("incidents/" + incident.getId(), viewerJwt)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sujet").value("INCIDENT"))
        .andExpect(jsonPath("$.alerteDepart").value(hostAlert.getId().toString()))
        .andExpect(
            jsonPath("$.conclusion")
                .value(
                    "Le serveur proxmox-02 est hors service ; aucune panne plus profonde au même moment"));
    rootCause("incidents/" + UUID.randomUUID(), adminJwt).andExpect(status().isNotFound());
    var foreign =
        incidents.saveAndFlush(new Incident(datacenter.getId(), "Ailleurs", Severity.WARNING, T0));
    rootCause("incidents/" + foreign.getId(), limitedJwt).andExpect(status().isNotFound());
  }

  // --- Saisie des dépendances
  // ---------------------------------------------------------------------

  private ResultActions declare(MonitoredService service, MonitoredService dependency, String jwt)
      throws Exception {
    return mockMvc.perform(
        post("/api/v1/dependances")
            .header("Authorization", "Bearer " + jwt)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"serviceId\":\""
                    + service.getId()
                    + "\",\"dependDeId\":\""
                    + dependency.getId()
                    + "\"}"));
  }

  @Test
  @DisplayName("Dépendances : création, doublon, soi-même, cycle refusé, suppression, droits")
  void dependencyRules() throws Exception {
    var vm = equipment("vm-ent", EquipmentType.VIRTUAL_MACHINE, campus);
    var ent = service(vm, "ENT", ServiceType.HTTPS);
    var api = service(vm, "API", ServiceType.HTTP);
    var db =
        service(
            equipment("vm-db", EquipmentType.VIRTUAL_MACHINE, campus),
            "PostgreSQL",
            ServiceType.TCP_PORT);

    String body =
        declare(ent, api, adminJwt)
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    // ADR 0058 : seul l'administrateur saisit les dépendances.
    declare(api, db, limitedJwt).andExpect(status().isForbidden());
    declare(api, db, adminJwt).andExpect(status().isCreated());
    declare(ent, api, adminJwt).andExpect(status().isConflict());
    declare(ent, ent, adminJwt).andExpect(status().isBadRequest());
    declare(db, ent, adminJwt)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("cycle")));
    declare(db, ent, viewerJwt).andExpect(status().isForbidden());

    mockMvc
        .perform(get("/api/v1/dependances").header("Authorization", "Bearer " + viewerJwt))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.service.nom == 'ENT')].dependDe.nom").value("API"));
    mockMvc
        .perform(get("/api/v1/dependances/services").header("Authorization", "Bearer " + viewerJwt))
        .andExpect(status().isOk());
    String id = JsonPath.read(body, "$.id");
    mockMvc
        .perform(delete("/api/v1/dependances/" + id).header("Authorization", "Bearer " + adminJwt))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(delete("/api/v1/dependances/" + id).header("Authorization", "Bearer " + adminJwt))
        .andExpect(status().isNotFound());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_entry WHERE action = 'SERVICE_DEPENDENCY_CREATED'",
                Integer.class))
        .isGreaterThanOrEqualTo(2);
  }

  @Test
  @DisplayName("Un compte limité ne relie pas un service d'un autre site")
  void dependencyScope() throws Exception {
    var mine =
        service(equipment("vm-a", EquipmentType.VIRTUAL_MACHINE, campus), "ENT", ServiceType.HTTPS);
    var theirs =
        service(
            equipment("vm-b", EquipmentType.VIRTUAL_MACHINE, datacenter),
            "LDAP",
            ServiceType.TCP_PORT);
    // L'opérateur ne saisit plus de dépendance (ADR 0058) : refus avant même le périmètre.
    declare(mine, theirs, limitedJwt).andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("Import CSV : lignes valides créées, lignes fautives expliquées, cycle refusé")
  void csvImport() throws Exception {
    var vm = equipment("vm-ent", EquipmentType.VIRTUAL_MACHINE, campus);
    var dbVm = equipment("vm-db", EquipmentType.VIRTUAL_MACHINE, campus);
    service(vm, "ENT", ServiceType.HTTPS);
    service(vm, "API", ServiceType.HTTP);
    service(dbVm, "PostgreSQL", ServiceType.TCP_PORT);
    String csv =
        "code_equipement;service;code_equipement;service\\n"
            + vm.getInventoryCode()
            + ";ENT;"
            + vm.getInventoryCode()
            + ";API\\n"
            + vm.getInventoryCode()
            + ";API;"
            + dbVm.getInventoryCode()
            + ";PostgreSQL\\n"
            + vm.getInventoryCode()
            + ";ENT;"
            + vm.getInventoryCode()
            + ";API\\n"
            + dbVm.getInventoryCode()
            + ";PostgreSQL;"
            + vm.getInventoryCode()
            + ";ENT\\n"
            + "INCONNU;X;"
            + vm.getInventoryCode()
            + ";ENT\\n"
            + "trois;colonnes;seulement\\n";

    mockMvc
        .perform(
            post("/api/v1/dependances/import")
                .header("Authorization", "Bearer " + adminJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"csv\":\"" + csv + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.creees").value(2))
        .andExpect(jsonPath("$.dejaPresentes").value(1))
        .andExpect(jsonPath("$.erreurs.length()").value(3))
        .andExpect(jsonPath("$.erreurs[0]").value("ligne 5 : refusée, elle fermerait un cycle"));
  }
}

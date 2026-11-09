package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.access.domain.SiteAccess;
import sn.ept.transdic1.access.repository.SiteAccessRepository;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.EquipmentStatus;
import sn.ept.transdic1.inventory.domain.EquipmentType;
import sn.ept.transdic1.inventory.domain.MonitoredService;
import sn.ept.transdic1.inventory.domain.ServiceType;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.MonitoredServiceRepository;
import sn.ept.transdic1.inventory.repository.SiteRepository;
import sn.ept.transdic1.security.domain.AppUser;
import sn.ept.transdic1.security.domain.Role;
import sn.ept.transdic1.security.repository.UserRepository;

/**
 * Bibliothèque de modèles (ADR 0022) : catalogue en lecture pour tous, aperçu et application
 * réservés aux administrateurs, idempotence, aperçu identique à l'application, règles limitées à
 * l'équipement et créées inactives, limites des sondes, secrets par référence, audit.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class TemplateApiTests {
  private static final String PASSWORD = "Mot-de-passe-test-123";
  private static final String SNMP_OPTIONS =
      "\"options\":{\"snmpVersion\":\"V2C\",\"communityReference\":\"env:SNMP_CAMPUS\"}";

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private PasswordEncoder encoder;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private MonitoredServiceRepository services;
  @Autowired private SiteAccessRepository access;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EntityManager em;

  private final String suffix = UUID.randomUUID().toString().substring(0, 8);
  private Equipment equipment;
  private Equipment other;
  private String admin;
  private String noc;
  private String reader;

  @BeforeEach
  void data() throws Exception {
    Site siteA = sites.save(new Site("MDA-" + suffix, "Modèles A " + suffix));
    Site siteB = sites.save(new Site("MDB-" + suffix, "Modèles B " + suffix));
    equipment = equipments.save(equipment("A", siteA));
    other = equipments.save(equipment("B", siteB));
    user("md-adm-", Role.ADMIN);
    user("md-noc-", Role.NOC_OPERATOR);
    AppUser limited = user("md-lec-", Role.VIEWER);
    access.saveAndFlush(new SiteAccess(limited, siteA, Instant.now()));
    equipments.flush();
    admin = login("md-adm-" + suffix);
    noc = login("md-noc-" + suffix);
    reader = login("md-lec-" + suffix);
  }

  private Equipment equipment(String tag, Site site) {
    String unique = UUID.randomUUID().toString();
    return new Equipment(
        "MD-" + tag + "-" + unique,
        "Serveur " + tag,
        "10.249." + (Math.floorMod(unique.hashCode(), 250) + 1) + "." + ("A".equals(tag) ? 1 : 2),
        EquipmentType.SERVER,
        EquipmentStatus.UP,
        site);
  }

  private AppUser user(String prefix, Role role) {
    return users.save(
        new AppUser(
            prefix + suffix, prefix + suffix + "@example.test", encoder.encode(PASSWORD), role));
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

  private ResultActions call(MockHttpServletRequestBuilder request, String jwt) throws Exception {
    return mockMvc.perform(
        request.header("Authorization", "Bearer " + jwt).contentType(MediaType.APPLICATION_JSON));
  }

  private ResultActions preview(UUID equipmentId, String body, String jwt) throws Exception {
    return call(
        post("/api/v1/equipements/" + equipmentId + "/modele/previsualiser").content(body), jwt);
  }

  private ResultActions apply(UUID equipmentId, String body, String jwt) throws Exception {
    return call(
        post("/api/v1/equipements/" + equipmentId + "/modele/appliquer").content(body), jwt);
  }

  private static String linux(String extraOptions) {
    return "{\"templateId\":\"linux-net-snmp\",\"options\":{\"snmpVersion\":\"V2C\","
        + "\"communityReference\":\"env:SNMP_CAMPUS\""
        + extraOptions
        + "}}";
  }

  private int count(String sql, Object... args) {
    em.flush();
    Integer value = jdbc.queryForObject(sql, Integer.class, args);
    return value == null ? 0 : value;
  }

  private int ruleCount() {
    return count(
        "SELECT count(*) FROM rule_equipment_scope WHERE equipment_id = ?", equipment.getId());
  }

  // ------------------------------------------------------------------ catalogue

  @Test
  void everyRoleReadsTheCatalogueWithFilters() throws Exception {
    call(get("/api/v1/modeles"), reader)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(10))
        .andExpect(jsonPath("$[?(@.id=='cisco-ios')].verified").value(false))
        .andExpect(jsonPath("$[?(@.id=='generic-snmp-host')].verified").value(true))
        .andExpect(jsonPath("$[?(@.id=='linux-net-snmp')].metricCount").value(6))
        .andExpect(jsonPath("$[?(@.id=='https-web-service')].probeCount").value(2));
    call(get("/api/v1/modeles").param("category", "UPS"), noc)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].id").value("apc-ups"));
    call(get("/api/v1/modeles").param("vendor", "cisco"), reader)
        .andExpect(jsonPath("$[0].id").value("cisco-ios"));
    call(get("/api/v1/modeles").param("q", "routeros"), reader)
        .andExpect(jsonPath("$[0].id").value("mikrotik-routeros"));
    call(get("/api/v1/modeles").param("sysDescr", "Linux web01 5.15.0-91-generic"), reader)
        .andExpect(jsonPath("$[?(@.id=='linux-net-snmp')]").isNotEmpty());
    mockMvc.perform(get("/api/v1/modeles")).andExpect(status().isUnauthorized());
  }

  @Test
  void theDetailGivesTheFullContentAndAnUnknownTemplateIsNotFound() throws Exception {
    call(get("/api/v1/modeles/linux-net-snmp"), reader)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(1))
        .andExpect(jsonPath("$.metrics[?(@.technicalKey=='cpu.usage')].invertPercent").value(true))
        .andExpect(
            jsonPath("$.metrics[?(@.technicalKey=='cpu.usage')].snmpOid")
                .value("1.3.6.1.4.1.2021.11.11.0"))
        .andExpect(jsonPath("$.rules.length()").value(3));
    call(get("/api/v1/modeles/inexistant"), reader).andExpect(status().isNotFound());
  }

  // ------------------------------------------------------------------ droits et périmètre

  @Test
  void onlyAdministratorsPreviewAndApply() throws Exception {
    preview(equipment.getId(), linux(""), noc).andExpect(status().isForbidden());
    apply(equipment.getId(), linux(""), noc).andExpect(status().isForbidden());
    apply(equipment.getId(), linux(""), reader).andExpect(status().isForbidden());
    assertThat(
            count(
                "SELECT count(*) FROM equipment_template WHERE equipment_id = ?",
                equipment.getId()))
        .isZero();
  }

  @Test
  void appliedTemplatesFollowTheSiteScope() throws Exception {
    apply(other.getId(), linux(""), admin).andExpect(status().isOk());

    call(get("/api/v1/equipements/" + other.getId() + "/modeles"), reader)
        .andExpect(status().isNotFound());
    call(get("/api/v1/equipements/" + other.getId() + "/modeles"), noc)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].templateId").value("linux-net-snmp"))
        .andExpect(jsonPath("$[0].templateVersion").value(1))
        .andExpect(jsonPath("$[0].appliedBy").value("md-adm-" + suffix))
        .andExpect(jsonPath("$[0].appliedAt").isNotEmpty());
    call(get("/api/v1/equipements/" + equipment.getId() + "/modeles"), reader)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
    apply(UUID.randomUUID(), linux(""), admin).andExpect(status().isNotFound());
    apply(equipment.getId(), "{\"templateId\":\"inexistant\"}", admin)
        .andExpect(status().isNotFound());
  }

  // ------------------------------------------------------------------ application

  @Test
  void applyingCreatesTheSnmpCollectionRulesAndProbes() throws Exception {
    apply(equipment.getId(), linux(",\"snmpPort\":1161"), admin)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.applied").value(true))
        .andExpect(jsonPath("$.snmpConfiguration").value("CREATED"))
        .andExpect(jsonPath("$.metrics.length()").value(6))
        .andExpect(jsonPath("$.metrics[?(@.action=='CREATE')]").isNotEmpty())
        .andExpect(jsonPath("$.rules.length()").value(3))
        .andExpect(jsonPath("$.probes.length()").value(2));

    var configuration =
        jdbc.queryForMap(
            "SELECT id, snmp_version, community_reference, snmp_port, active FROM"
                + " collection_configuration WHERE equipment_id = ? AND mode = 'SNMP'",
            equipment.getId());
    assertThat(configuration.get("community_reference")).isEqualTo("env:SNMP_CAMPUS");
    assertThat(configuration.get("snmp_port")).isEqualTo(1161);
    var cpu =
        jdbc.queryForMap(
            "SELECT snmp_oid, invert_percent, unit FROM metric_definition WHERE configuration_id = ?"
                + " AND technical_key = 'cpu.usage'",
            configuration.get("id"));
    assertThat(cpu.get("snmp_oid")).isEqualTo("1.3.6.1.4.1.2021.11.11.0");
    assertThat(cpu.get("invert_percent")).isEqualTo(true);

    // Règles créées inactives (suggestions) et limitées à cet équipement seulement.
    List<Map<String, Object>> rules =
        jdbc.queryForList(
            "SELECT r.name, r.active, r.metric_type FROM alert_rule r JOIN rule_equipment_scope s"
                + " ON s.rule_id = r.id WHERE s.equipment_id = ?",
            equipment.getId());
    assertThat(rules).hasSize(3).allMatch(r -> Boolean.FALSE.equals(r.get("active")));
    assertThat(rules)
        .extracting(r -> (String) r.get("name"))
        .allMatch(n -> n.endsWith(equipment.getInventoryCode()));
    assertThat(
            count(
                "SELECT count(*) FROM rule_site_scope s JOIN alert_rule r ON r.id = s.rule_id"
                    + " WHERE r.name LIKE ?",
                "%" + equipment.getInventoryCode()))
        .isZero();

    assertThat(services.findByEquipmentIdOrderByName(equipment.getId()))
        .extracting(MonitoredService::getName)
        .containsExactly("Ping", "SSH");
    assertThat(
            count(
                "SELECT count(*) FROM audit_entry WHERE action = 'TEMPLATE_APPLIED' AND target_id = ?",
                equipment.getId()))
        .isOne();
  }

  @Test
  void reapplyingTheSameTemplateCreatesNothingTwice() throws Exception {
    apply(equipment.getId(), linux(""), admin).andExpect(status().isOk());
    int metricsBefore =
        count(
            "SELECT count(*) FROM metric_definition d JOIN collection_configuration c"
                + " ON c.id = d.configuration_id WHERE c.equipment_id = ?",
            equipment.getId());

    apply(equipment.getId(), "{\"templateId\":\"linux-net-snmp\"}", admin)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.snmpConfiguration").value("EXISTING"))
        .andExpect(jsonPath("$.metrics[?(@.action=='CREATE')]").isEmpty())
        .andExpect(jsonPath("$.rules[?(@.action=='CREATE')]").isEmpty())
        .andExpect(jsonPath("$.probes[?(@.action=='CREATE')]").isEmpty())
        .andExpect(jsonPath("$.probes[0].action").value("PRESENT"));

    assertThat(
            count(
                "SELECT count(*) FROM metric_definition d JOIN collection_configuration c"
                    + " ON c.id = d.configuration_id WHERE c.equipment_id = ?",
                equipment.getId()))
        .isEqualTo(metricsBefore);
    assertThat(ruleCount()).isEqualTo(3);
    assertThat(services.findByEquipmentIdOrderByName(equipment.getId())).hasSize(2);
    assertThat(
            count(
                "SELECT count(*) FROM equipment_template WHERE equipment_id = ?",
                equipment.getId()))
        .isOne();
  }

  @Test
  void anotherTemplateAddsWithoutDeletingAndKeepsExistingMetrics() throws Exception {
    apply(equipment.getId(), linux(""), admin).andExpect(status().isOk());

    apply(equipment.getId(), "{\"templateId\":\"generic-snmp-host\"}", admin)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.metrics[?(@.key=='system.uptime')].action").value("PRESENT"))
        .andExpect(jsonPath("$.metrics[?(@.key=='interfaces.count')].action").value("CREATE"))
        // « Ping » existe déjà (modèle Linux) : la sonde n'est pas recréée.
        .andExpect(jsonPath("$.probes[?(@.name=='Ping')].action").value("PRESENT"));

    call(get("/api/v1/equipements/" + equipment.getId() + "/modeles"), admin)
        .andExpect(jsonPath("$.length()").value(2));
    assertThat(ruleCount()).isEqualTo(4);
    assertThat(services.findByEquipmentIdOrderByName(equipment.getId())).hasSize(2);
  }

  @Test
  void activateRulesAndOptionalSectionsAreHonoured() throws Exception {
    apply(equipment.getId(), linux(",\"activateRules\":true,\"createProbes\":false"), admin)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.probes[0].action").value("SKIPPED"));
    assertThat(
            count(
                "SELECT count(*) FROM alert_rule r JOIN rule_equipment_scope s ON s.rule_id = r.id"
                    + " WHERE s.equipment_id = ? AND r.active",
                equipment.getId()))
        .isEqualTo(3);
    assertThat(services.findByEquipmentIdOrderByName(equipment.getId())).isEmpty();

    apply(other.getId(), linux(",\"createRules\":false"), admin)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rules[0].action").value("SKIPPED"));
    assertThat(
            count(
                "SELECT count(*) FROM rule_equipment_scope WHERE equipment_id = ?", other.getId()))
        .isZero();
  }

  @Test
  void thePreviewChangesNothingAndEqualsTheApplication() throws Exception {
    String previewBody =
        preview(equipment.getId(), linux(""), admin)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.applied").value(false))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(
            count(
                "SELECT count(*) FROM collection_configuration WHERE equipment_id = ?",
                equipment.getId()))
        .isZero();
    assertThat(ruleCount()).isZero();
    assertThat(services.findByEquipmentIdOrderByName(equipment.getId())).isEmpty();

    String applyBody =
        apply(equipment.getId(), linux(""), admin)
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    for (String section :
        List.of("$.snmpConfiguration", "$.metrics", "$.rules", "$.probes", "$.eventRules")) {
      Object expected = JsonPath.read(previewBody, section);
      Object actual = JsonPath.read(applyBody, section);
      assertThat(actual).as(section).isEqualTo(expected);
    }
  }

  @Test
  void eventRulesAreOnlySuggested() throws Exception {
    apply(equipment.getId(), "{\"templateId\":\"cisco-ios\"," + SNMP_OPTIONS + "}", admin)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.eventRules.length()").value(2))
        .andExpect(jsonPath("$.eventRules[0].action").value("SKIPPED"));
  }

  @Test
  void aProbeOnlyTemplateNeedsNoSnmp() throws Exception {
    apply(equipment.getId(), "{\"templateId\":\"https-web-service\"}", admin)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.snmpConfiguration").value("NOT_NEEDED"))
        .andExpect(jsonPath("$.probes.length()").value(2));
    assertThat(
            count(
                "SELECT count(*) FROM collection_configuration WHERE equipment_id = ?",
                equipment.getId()))
        .isZero();
    assertThat(ruleCount()).isEqualTo(2);
  }

  // ------------------------------------------------------------------ validation et limites

  @Test
  void snmpOptionsAreRequiredAndValidatedLikeTheSnmpConfiguration() throws Exception {
    apply(equipment.getId(), "{\"templateId\":\"linux-net-snmp\"}", admin)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("SNMP")));
    // Communauté en clair refusée : seules les références env:NOM sont acceptées.
    apply(
            equipment.getId(),
            "{\"templateId\":\"linux-net-snmp\",\"options\":{\"snmpVersion\":\"V2C\","
                + "\"communityReference\":\"public\"}}",
            admin)
        .andExpect(status().isBadRequest());
    apply(
            equipment.getId(),
            "{\"templateId\":\"linux-net-snmp\",\"options\":{\"snmpVersion\":\"V3\","
                + "\"secretReference\":\"env:SNMP_V3\"}}",
            admin)
        .andExpect(status().isBadRequest());
    apply(equipment.getId(), linux(",\"snmpPort\":70000"), admin)
        .andExpect(status().isBadRequest());
    apply(equipment.getId(), "{\"templateId\":\"\"}", admin).andExpect(status().isBadRequest());
    assertThat(
            count(
                "SELECT count(*) FROM collection_configuration WHERE equipment_id = ?",
                equipment.getId()))
        .isZero();
  }

  @Test
  void anEquipmentWithoutAUsableAddressCannotReceiveSnmpMetrics() throws Exception {
    em.flush();
    jdbc.update(
        "UPDATE equipment SET management_address = '0.0.0.0' WHERE id = ?", equipment.getId());
    em.clear();

    apply(equipment.getId(), linux(""), admin)
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.error").value(org.hamcrest.Matchers.containsString("adresse de gestion")));
  }

  @Test
  void theProbeLimitIsEnforcedAndNothingIsCreated() throws Exception {
    for (int i = 0; i < 49; i++) {
      var probe = new MonitoredService(equipment, "Port " + i, ServiceType.TCP_PORT);
      probe.setPort(10_000 + i);
      services.save(probe);
    }
    services.flush();

    // Le modèle Linux ajoute deux sondes : 49 + 2 > 50.
    preview(equipment.getId(), linux(""), admin)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("50")));
    apply(equipment.getId(), linux(""), admin).andExpect(status().isBadRequest());
    assertThat(
            count(
                "SELECT count(*) FROM collection_configuration WHERE equipment_id = ?",
                equipment.getId()))
        .isZero();
    assertThat(ruleCount()).isZero();
  }

  @Test
  void aRuleNameTakenByAnotherRuleIsSkippedNotOverwritten() throws Exception {
    String name = "CPU élevé — " + equipment.getInventoryCode();
    call(
            post("/api/v1/regles-alertes")
                .content(
                    "{\"name\":\""
                        + name
                        + "\",\"metricType\":\"cpu.usage\",\"operator\":\"GREATER_THAN\","
                        + "\"threshold\":50,\"confirmationSeconds\":0,\"initialSeverity\":\"INFO\"}"),
            admin)
        .andExpect(status().isCreated());

    apply(equipment.getId(), linux(""), admin)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rules[?(@.name=='" + name + "')].action").value("SKIPPED"));
    assertThat(ruleCount()).isEqualTo(2);
  }
}

package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
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
import sn.ept.transdic1.events.domain.EventSeverity;
import sn.ept.transdic1.events.domain.EventSource;
import sn.ept.transdic1.events.domain.NetworkEvent;
import sn.ept.transdic1.events.repository.NetworkEventRepository;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.EquipmentStatus;
import sn.ept.transdic1.inventory.domain.EquipmentType;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.SiteRepository;
import sn.ept.transdic1.security.domain.AppUser;
import sn.ept.transdic1.security.domain.Role;
import sn.ept.transdic1.security.repository.UserRepository;

/** API des événements réseau et des règles d'événements : périmètre, rôles, validation, audit. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class EventApiTests {
  private static final String PASSWORD = "Mot-de-passe-test-123";
  private static final String RULES = "/api/v1/regles-evenements";

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private PasswordEncoder encoder;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private SiteAccessRepository access;
  @Autowired private NetworkEventRepository events;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private jakarta.persistence.EntityManager em;

  private final String suffix = UUID.randomUUID().toString().substring(0, 8);
  private Site siteA;
  private Site siteB;
  private Equipment equipmentA;
  private Equipment equipmentB;
  private String admin;
  private String noc;
  private String viewer;

  @BeforeEach
  void data() throws Exception {
    siteA = sites.save(new Site("EVA-" + suffix, "Site A"));
    siteB = sites.save(new Site("EVB-" + suffix, "Site B"));
    equipmentA = equipments.save(equipment("A", siteA));
    equipmentB = equipments.save(equipment("B", siteB));
    AppUser adminUser = user("ev-adm-", Role.ADMIN);
    AppUser limited = user("ev-noc-", Role.NOC_OPERATOR);
    user("ev-vwr-", Role.VIEWER);
    access.saveAndFlush(new SiteAccess(limited, siteA, Instant.now()));
    assertThat(adminUser).isNotNull();
    Instant now = Instant.now();
    events.save(
        new NetworkEvent(
            equipmentA,
            EventSource.SYSLOG,
            EventSeverity.ERROR,
            equipmentA.getManagementAddress(),
            "Disque plein sur /var",
            "<11>disque plein sur /var",
            null,
            now.minusSeconds(60)));
    events.save(
        new NetworkEvent(
            equipmentA,
            EventSource.SNMP_TRAP,
            EventSeverity.NOTICE,
            equipmentA.getManagementAddress(),
            "linkUp (interface rétablie)",
            "ifIndex = 3",
            "1.3.6.1.6.3.1.1.5.4",
            now.minusSeconds(30)));
    events.saveAndFlush(
        new NetworkEvent(
            equipmentB,
            EventSource.SYSLOG,
            EventSeverity.CRITICAL,
            equipmentB.getManagementAddress(),
            "Alimentation en panne",
            "psu fail",
            null,
            now));
    admin = login("ev-adm-" + suffix);
    noc = login("ev-noc-" + suffix);
    viewer = login("ev-vwr-" + suffix);
  }

  private Equipment equipment(String tag, Site site) {
    return new Equipment(
        "EV-" + tag + "-" + suffix,
        "Équipement " + tag,
        "10.243."
            + ThreadLocalRandom.current().nextInt(1, 254)
            + "."
            + ThreadLocalRandom.current().nextInt(1, 254),
        EquipmentType.ROUTER,
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

  private ResultActions call(MockHttpServletRequestBuilder request, String token) throws Exception {
    return mockMvc.perform(
        request.header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON));
  }

  private String ruleJson(String name, String extra) {
    return "{\"name\":\""
        + name
        + "\",\"alertSeverity\":\"WARNING\",\"dedupWindowSeconds\":300,\"active\":true"
        + extra
        + "}";
  }

  // ------------------------------------------------------------------ événements

  @Test
  void laListeRespecteLePérimètreEtLesFiltres() throws Exception {
    String mine = "&equipmentId=" + equipmentA.getId();
    call(get("/api/v1/evenements?size=100" + mine), viewer)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(2))
        .andExpect(jsonPath("$.content[0].title").value("linkUp (interface rétablie)"))
        .andExpect(jsonPath("$.content[0].equipmentName").value("Équipement A"));

    call(get("/api/v1/evenements?source=SYSLOG" + mine), admin)
        .andExpect(jsonPath("$.content.length()").value(1));
    call(get("/api/v1/evenements?minSeverity=ERROR" + mine), admin)
        .andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(jsonPath("$.content[0].severity").value("ERROR"));
    call(get("/api/v1/evenements?q=DISQUE" + mine), admin)
        .andExpect(jsonPath("$.content.length()").value(1));
    call(get("/api/v1/evenements?from=" + Instant.now().minusSeconds(45) + mine), admin)
        .andExpect(jsonPath("$.content.length()").value(1));

    // L'opérateur limité au site A ne voit jamais le site B.
    call(get("/api/v1/evenements?size=500"), noc)
        .andExpect(
            jsonPath(
                "$.content[*].equipmentId",
                Matchers.not(Matchers.hasItem(equipmentB.getId().toString()))));
    call(get("/api/v1/evenements?equipmentId=" + equipmentB.getId()), noc)
        .andExpect(jsonPath("$.content.length()").value(0));
    call(get("/api/v1/evenements?equipmentId=" + equipmentB.getId()), admin)
        .andExpect(jsonPath("$.content.length()").value(1));
  }

  @Test
  void laListeExigeUneAuthentification() throws Exception {
    mockMvc.perform(get("/api/v1/evenements")).andExpect(status().isUnauthorized());
  }

  // ------------------------------------------------------------------ règles

  @Test
  void lesRèglesParDéfautSontLivréesInactives() throws Exception {
    call(get(RULES + "?size=100"), viewer)
        .andExpect(status().isOk())
        .andExpect(
            jsonPath(
                "$.content[?(@.name == 'Interface coupée (linkDown)')].active",
                Matchers.contains(false)))
        .andExpect(
            jsonPath(
                "$.content[?(@.name == 'Interface coupée (linkDown)')].resolveOid",
                Matchers.contains("1.3.6.1.6.3.1.1.5.4")));
  }

  @Test
  void créationModificationSuppressionAuditées() throws Exception {
    String body =
        call(
                post(RULES)
                    .content(
                        ruleJson(
                            "Panne alim " + suffix,
                            ",\"source\":\"SYSLOG\",\"messageRegex\":\"power supply (fail|down)\","
                                + "\"siteIds\":[\""
                                + siteA.getId()
                                + "\"]")),
                admin)
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.siteIds[0]").value(siteA.getId().toString()))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String id = JsonPath.read(body, "$.id");

    call(
            put(RULES + "/" + id)
                .content(
                    ruleJson("Panne alim " + suffix, ",\"minSeverity\":\"CRITICAL\"")
                        .replace("WARNING", "CRITICAL")),
            admin)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.alertSeverity").value("CRITICAL"))
        .andExpect(jsonPath("$.siteIds.length()").value(0));

    call(delete(RULES + "/" + id), admin).andExpect(status().isNoContent());
    call(get(RULES + "/" + id), admin).andExpect(status().isNotFound());
    em.flush();

    // Même transaction de test : même horodatage, d'où la comparaison sans ordre.
    assertThat(
            jdbc.queryForList(
                "SELECT action FROM audit_entry WHERE target_id = ?::uuid", String.class, id))
        .containsExactlyInAnyOrder(
            "EVENT_RULE_CREATED", "EVENT_RULE_UPDATED", "EVENT_RULE_DELETED");
  }

  @Test
  void seulLAdministrateurÉcritEtTeste() throws Exception {
    String json = ruleJson("Refusée " + suffix, ",\"minSeverity\":\"ERROR\"");
    call(post(RULES).content(json), noc).andExpect(status().isForbidden());
    call(post(RULES).content(json), viewer).andExpect(status().isForbidden());
    String test =
        "{\"rule\":" + json + ",\"source\":\"SYSLOG\",\"severity\":\"ERROR\",\"text\":\"x\"}";
    call(post(RULES + "/tester").content(test), noc).andExpect(status().isForbidden());
  }

  @Test
  void validationAvecMessagesEnFrançais() throws Exception {
    // Aucun critère : la règle s'appliquerait à tout événement.
    call(post(RULES).content(ruleJson("Vide " + suffix, "")), admin)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error", Matchers.containsString("critère")));
    call(post(RULES).content(ruleJson("Regex " + suffix, ",\"messageRegex\":\"(abc\"")), admin)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error", Matchers.containsString("invalide")));
    call(
            post(RULES).content(ruleJson("Lente " + suffix, ",\"messageRegex\":\"(a+)+\\\\1$\"")),
            admin)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error", Matchers.containsString("coûteuse")));
    call(
            post(RULES)
                .content(
                    ruleJson(
                        "Oid syslog " + suffix,
                        ",\"source\":\"SYSLOG\",\"oidPrefix\":\"1.3.6.1.6.3.1.1.5.3\"")),
            admin)
        .andExpect(status().isBadRequest());
    call(post(RULES).content(ruleJson("Oid " + suffix, ",\"oidPrefix\":\"1.3.x\"")), admin)
        .andExpect(status().isBadRequest());
    call(
            post(RULES)
                .content(
                    ruleJson(
                        "Interface coupée (linkDown)", ",\"oidPrefix\":\"1.3.6.1.6.3.1.1.5.3\"")),
            admin)
        .andExpect(status().isConflict());
  }

  @Test
  void leTestNEnregistreRien() throws Exception {
    long before = events.count();
    String test =
        "{\"rule\":"
            + ruleJson(
                "Essai " + suffix,
                ",\"source\":\"SNMP_TRAP\",\"oidPrefix\":\"1.3.6.1.6.3.1.1.5.3\","
                    + "\"resolveOid\":\"1.3.6.1.6.3.1.1.5.4\"")
            + ",\"source\":\"SNMP_TRAP\",\"severity\":\"ERROR\",\"trapOid\":\"1.3.6.1.6.3.1.1.5.3\","
            + "\"text\":\"ifIndex = 2\"}";
    call(post(RULES + "/tester").content(test), admin)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.opens").value(true))
        .andExpect(jsonPath("$.resolves").value(false));
    call(
            post(RULES + "/tester")
                .content(
                    test.replace(
                        "\"trapOid\":\"1.3.6.1.6.3.1.1.5.3\"",
                        "\"trapOid\":\"1.3.6.1.6.3.1.1.5.4\"")),
            admin)
        .andExpect(jsonPath("$.opens").value(false))
        .andExpect(jsonPath("$.resolves").value(true));

    assertThat(events.count()).isEqualTo(before);
    Integer saved =
        jdbc.queryForObject(
            "SELECT count(*) FROM event_rule WHERE name = ?", Integer.class, "Essai " + suffix);
    assertThat(saved).isZero();
  }
}

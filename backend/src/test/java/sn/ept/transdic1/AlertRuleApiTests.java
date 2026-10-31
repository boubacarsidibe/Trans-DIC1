package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.alerting.repository.AlertRepository;
import sn.ept.transdic1.collection.dto.MeasureInput;
import sn.ept.transdic1.collection.service.MeasureIngestionService;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.EquipmentStatus;
import sn.ept.transdic1.inventory.domain.EquipmentType;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.SiteRepository;
import sn.ept.transdic1.security.domain.AppUser;
import sn.ept.transdic1.security.domain.Role;
import sn.ept.transdic1.security.repository.UserRepository;

/** Administration des règles d'alerte : droits, validation, portée, suppression et effet réel. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AlertRuleApiTests {
  private static final String PASSWORD = "Mot-de-passe-test-123";
  private static final String BASE = "/api/v1/regles-alertes";

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private PasswordEncoder encoder;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private AlertRepository alerts;
  @Autowired private MeasureIngestionService ingestion;
  @Autowired private sn.ept.transdic1.audit.repository.AuditEntryRepository auditEntries;

  private Site site;
  private Equipment equipment;
  private String admin;
  private String noc;
  private String viewer;

  @BeforeEach
  void data() throws Exception {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    users.save(
        new AppUser(
            "adm-" + suffix,
            "adm-" + suffix + "@example.test",
            encoder.encode(PASSWORD),
            Role.ADMIN));
    users.save(
        new AppUser(
            "noc-" + suffix,
            "noc-" + suffix + "@example.test",
            encoder.encode(PASSWORD),
            Role.NOC_OPERATOR));
    users.save(
        new AppUser(
            "vwr-" + suffix,
            "vwr-" + suffix + "@example.test",
            encoder.encode(PASSWORD),
            Role.VIEWER));
    admin = login("adm-" + suffix);
    noc = login("noc-" + suffix);
    viewer = login("vwr-" + suffix);
    String unique = UUID.randomUUID().toString();
    site = sites.save(new Site("RUL-" + suffix, "Site des règles"));
    equipment =
        equipments.save(
            new Equipment(
                "RUL-EQ-" + unique,
                "Serveur des règles",
                "10.241."
                    + (Math.floorMod(unique.hashCode(), 250) + 1)
                    + "."
                    + (Math.floorMod(unique.substring(9).hashCode(), 250) + 1),
                EquipmentType.SERVER,
                EquipmentStatus.UP,
                site));
    equipments.flush();
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

  private static String rule(String name, String operator, String threshold, String recovery) {
    return rule(name, operator, threshold, recovery, "[]", "[]");
  }

  private String ruleOnEquipment(String name) {
    return rule(name, "GREATER_THAN", "90", "80", "[]", "[\"" + equipment.getId() + "\"]");
  }

  private static String rule(
      String name,
      String operator,
      String threshold,
      String recovery,
      String siteIds,
      String equipmentIds) {
    return "{\"name\":\""
        + name
        + "\",\"metricType\":\"cpu.usage\",\"operator\":\""
        + operator
        + "\",\"threshold\":"
        + threshold
        + (recovery == null ? "" : ",\"recoveryThreshold\":" + recovery)
        + ",\"unit\":\"%\",\"confirmationSeconds\":0,\"initialSeverity\":\"CRITICAL\""
        + ",\"siteIds\":"
        + siteIds
        + ",\"equipmentIds\":"
        + equipmentIds
        + "}";
  }

  private ResultActions send(String method, String jwt, String url, String body) throws Exception {
    var request =
        switch (method) {
          case "POST" -> post(url);
          case "PUT" -> put(url);
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

  private String create(String body) throws Exception {
    var response =
        send("POST", admin, BASE, body).andExpect(status().isCreated()).andReturn().getResponse();
    return JsonPath.read(response.getContentAsString(), "$.id");
  }

  @Test
  void everyRoleReadsButOnlyAnAdministratorWrites() throws Exception {
    String id = create(ruleOnEquipment("lecture-" + UUID.randomUUID()));

    for (String jwt : new String[] {admin, noc, viewer}) {
      send("GET", jwt, BASE + "/" + id, null).andExpect(status().isOk());
      send("GET", jwt, BASE, null).andExpect(status().isOk());
    }
    send("GET", null, BASE, null).andExpect(status().isUnauthorized());
    for (String jwt : new String[] {noc, viewer}) {
      send("POST", jwt, BASE, ruleOnEquipment("interdit-" + UUID.randomUUID()))
          .andExpect(status().isForbidden());
      send("PUT", jwt, BASE + "/" + id, ruleOnEquipment("interdit-" + UUID.randomUUID()))
          .andExpect(status().isForbidden());
      send("DELETE", jwt, BASE + "/" + id, null).andExpect(status().isForbidden());
    }
  }

  @Test
  void creatingARuleReturnsItWithItsScope() throws Exception {
    String body =
        rule(
            "cpu-" + UUID.randomUUID(),
            "GREATER_THAN",
            "90",
            "80",
            "[\"" + site.getId() + "\"]",
            "[\"" + equipment.getId() + "\"]");

    send("POST", admin, BASE, body)
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.metricType").value("cpu.usage"))
        .andExpect(jsonPath("$.operator").value("GREATER_THAN"))
        .andExpect(jsonPath("$.threshold").value(90))
        .andExpect(jsonPath("$.recoveryThreshold").value(80))
        .andExpect(jsonPath("$.active").value(true))
        .andExpect(jsonPath("$.siteIds[0]").value(site.getId().toString()))
        .andExpect(jsonPath("$.equipmentIds[0]").value(equipment.getId().toString()));
  }

  @Test
  void invalidRulesAreRejectedWithAClearError() throws Exception {
    // seuil de retour du mauvais côté du seuil d'ouverture
    send("POST", admin, BASE, rule("r1-" + UUID.randomUUID(), "GREATER_THAN", "90", "95"))
        .andExpect(status().isBadRequest());
    send("POST", admin, BASE, rule("r2-" + UUID.randomUUID(), "LESS_THAN", "10", "5"))
        .andExpect(status().isBadRequest());
    // pas de seuil de retour pour EQUAL
    send("POST", admin, BASE, rule("r3-" + UUID.randomUUID(), "EQUAL", "0", "0"))
        .andExpect(status().isBadRequest());
    // champs invalides
    send("POST", admin, BASE, rule("", "GREATER_THAN", "90", null))
        .andExpect(status().isBadRequest());
    send(
            "POST",
            admin,
            BASE,
            rule("r4-" + UUID.randomUUID(), "GREATER_THAN", "90", null)
                .replace("cpu.usage", "CPU Usage"))
        .andExpect(status().isBadRequest());
    send("POST", admin, BASE, rule("r5-" + UUID.randomUUID(), "SUPERIEUR", "90", null))
        .andExpect(status().isBadRequest());
    // portée inconnue
    send(
            "POST",
            admin,
            BASE,
            rule(
                "r6-" + UUID.randomUUID(),
                "GREATER_THAN",
                "90",
                null,
                "[\"" + UUID.randomUUID() + "\"]",
                "[]"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void twoRulesCannotShareAName() throws Exception {
    String name = "unique-" + UUID.randomUUID();
    create(ruleOnEquipment(name));

    send("POST", admin, BASE, ruleOnEquipment(name)).andExpect(status().isConflict());
  }

  @Test
  void updatingReplacesTheFieldsAndTheScope() throws Exception {
    String id = create(ruleOnEquipment("avant-" + UUID.randomUUID()));

    send(
            "PUT",
            admin,
            BASE + "/" + id,
            rule(
                "apres-" + UUID.randomUUID(),
                "GREATER_OR_EQUAL",
                "70",
                "60",
                "[\"" + site.getId() + "\"]",
                "[]"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.operator").value("GREATER_OR_EQUAL"))
        .andExpect(jsonPath("$.threshold").value(70))
        .andExpect(jsonPath("$.siteIds.length()").value(1))
        .andExpect(jsonPath("$.equipmentIds.length()").value(0));
    // Le journal garde l'ancienne et la nouvelle valeur (retour arrière d'un seuil, S4-SEUILS).
    String details =
        auditEntries
            .findByTargetTypeAndTargetIdOrderByPerformedAtDesc(
                "ALERT_RULE",
                UUID.fromString(id),
                org.springframework.data.domain.Pageable.ofSize(1))
            .getContent()
            .getFirst()
            .getDetailsSanitized();
    org.assertj.core.api.Assertions.assertThat(details)
        .contains("avant: cpu.usage GREATER_THAN 90", "retour 80")
        .contains("après: cpu.usage GREATER_OR_EQUAL 70", "retour 60");
    send("PUT", admin, BASE + "/" + UUID.randomUUID(), ruleOnEquipment("x-" + UUID.randomUUID()))
        .andExpect(status().isNotFound());
  }

  @Test
  void deactivatedRulesStopRaisingAlerts() throws Exception {
    String name = "desactivee-" + UUID.randomUUID();
    String id = create(ruleOnEquipment(name));
    send(
            "PUT",
            admin,
            BASE + "/" + id,
            ruleOnEquipment(name).replace("\"unit\"", "\"active\":false,\"unit\""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.active").value(false));

    ingestion.ingest(
        equipment.getId(),
        List.of(
            new MeasureInput(
                "cpu.usage",
                new BigDecimal("99"),
                "%",
                null,
                Instant.now().minus(1, ChronoUnit.MINUTES))));

    assertThat(
            alerts
                .findByEquipmentIdOrderByTriggeredAtDesc(
                    equipment.getId(), org.springframework.data.domain.Pageable.unpaged())
                .getContent())
        .isEmpty();
  }

  @Test
  void aRuleCreatedThroughTheApiRaisesAlertsOnItsEquipment() throws Exception {
    create(ruleOnEquipment("reelle-" + UUID.randomUUID()));

    ingestion.ingest(
        equipment.getId(),
        List.of(
            new MeasureInput(
                "cpu.usage",
                new BigDecimal("97"),
                "%",
                null,
                Instant.now().minus(1, ChronoUnit.MINUTES))));

    assertThat(
            alerts
                .findByEquipmentIdOrderByTriggeredAtDesc(
                    equipment.getId(), org.springframework.data.domain.Pageable.unpaged())
                .getContent())
        .hasSize(1);
  }

  @Test
  void aRuleWithoutAlertsCanBeDeletedButOneThatRaisedAlertsCannot() throws Exception {
    String free = create(ruleOnEquipment("libre-" + UUID.randomUUID()));
    send("DELETE", admin, BASE + "/" + free, null).andExpect(status().isNoContent());
    send("GET", admin, BASE + "/" + free, null).andExpect(status().isNotFound());

    String used = create(ruleOnEquipment("utilisee-" + UUID.randomUUID()));
    ingestion.ingest(
        equipment.getId(),
        List.of(
            new MeasureInput(
                "cpu.usage",
                new BigDecimal("99"),
                "%",
                null,
                Instant.now().minus(1, ChronoUnit.MINUTES))));

    send("DELETE", admin, BASE + "/" + used, null).andExpect(status().isConflict());
    send("GET", admin, BASE + "/" + used, null).andExpect(status().isOk());
  }
}

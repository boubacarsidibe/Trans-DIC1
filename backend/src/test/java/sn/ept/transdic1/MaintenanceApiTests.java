package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.alerting.domain.AlertRule;
import sn.ept.transdic1.alerting.domain.ComparisonOperator;
import sn.ept.transdic1.alerting.domain.RuleEquipmentScope;
import sn.ept.transdic1.alerting.domain.Severity;
import sn.ept.transdic1.alerting.repository.AlertEventRepository;
import sn.ept.transdic1.alerting.repository.AlertRepository;
import sn.ept.transdic1.alerting.repository.AlertRuleRepository;
import sn.ept.transdic1.alerting.repository.RuleEquipmentScopeRepository;
import sn.ept.transdic1.alerting.service.MaintenanceService;
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

/** Planification des maintenances : droits, validation, états calculés, effet sur les alertes. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class MaintenanceApiTests {
  private static final String PASSWORD = "Mot-de-passe-test-123";
  private static final String BASE = "/api/v1/maintenances";

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private PasswordEncoder encoder;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private AlertRuleRepository rules;
  @Autowired private RuleEquipmentScopeRepository scopes;
  @Autowired private AlertRepository alerts;
  @Autowired private AlertEventRepository events;
  @Autowired private MeasureIngestionService ingestion;
  @Autowired private MaintenanceService maintenanceService;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EntityManager em;

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
    site = sites.save(new Site("MNT-" + suffix, "Site en maintenance"));
    equipment =
        equipments.save(
            new Equipment(
                "MNT-EQ-" + unique,
                "Serveur en maintenance",
                "10.235."
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

  private static Instant in(long hours) {
    return Instant.now().plus(hours, ChronoUnit.HOURS);
  }

  private String onEquipment(Instant start, Instant end) {
    return "{\"equipmentId\":\""
        + equipment.getId()
        + "\",\"startAt\":\""
        + start
        + "\",\"endAt\":\""
        + end
        + "\",\"reason\":\"Mise à jour du noyau\"}";
  }

  private ResultActions plan(String jwt, String body) throws Exception {
    return mockMvc.perform(
        post(BASE)
            .header("Authorization", "Bearer " + jwt)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  private String create(String body) throws Exception {
    var response =
        plan(noc, body)
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(response, "$.id");
  }

  @Test
  void everyRoleReadsButOnlyOperatorsAndAdministratorsWrite() throws Exception {
    create(onEquipment(in(1), in(2)));

    for (String jwt : new String[] {admin, noc, viewer}) {
      mockMvc
          .perform(get(BASE).header("Authorization", "Bearer " + jwt))
          .andExpect(status().isOk());
    }
    mockMvc.perform(get(BASE)).andExpect(status().isUnauthorized());
    plan(viewer, onEquipment(in(1), in(2))).andExpect(status().isForbidden());
  }

  @Test
  void aMaintenanceOnAnEquipmentOrOnASiteIsPlannedAndAudited() throws Exception {
    plan(admin, onEquipment(in(1), in(3)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.state").value("PLANNED"))
        .andExpect(jsonPath("$.equipmentName").value("Serveur en maintenance"))
        .andExpect(jsonPath("$.siteId").doesNotExist());

    plan(
            noc,
            "{\"siteId\":\""
                + site.getId()
                + "\",\"startAt\":\""
                + in(-1)
                + "\",\"endAt\":\""
                + in(2)
                + "\",\"reason\":\"Coupure électrique\"}")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.state").value("IN_PROGRESS"))
        .andExpect(jsonPath("$.siteName").value("Site en maintenance"));

    em.flush();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_entry WHERE action = 'MAINTENANCE_PLANNED'",
                Integer.class))
        .isGreaterThanOrEqualTo(2);
  }

  @Test
  void invalidPlanningsAreRejected() throws Exception {
    // ni site ni équipement, ou les deux
    plan(admin, "{\"startAt\":\"" + in(1) + "\",\"endAt\":\"" + in(2) + "\",\"reason\":\"x\"}")
        .andExpect(status().isBadRequest());
    plan(
            admin,
            "{\"siteId\":\""
                + site.getId()
                + "\",\"equipmentId\":\""
                + equipment.getId()
                + "\",\"startAt\":\""
                + in(1)
                + "\",\"endAt\":\""
                + in(2)
                + "\",\"reason\":\"x\"}")
        .andExpect(status().isBadRequest());
    // fin avant le début, maintenance déjà terminée, motif vide, cible inconnue
    plan(admin, onEquipment(in(2), in(1))).andExpect(status().isBadRequest());
    plan(admin, onEquipment(in(-3), in(-1))).andExpect(status().isBadRequest());
    plan(admin, onEquipment(in(1), in(2)).replace("Mise à jour du noyau", ""))
        .andExpect(status().isBadRequest());
    plan(
            admin,
            onEquipment(in(1), in(2))
                .replace(equipment.getId().toString(), UUID.randomUUID().toString()))
        .andExpect(status().isBadRequest());
  }

  @Test
  void theListFiltersByEffectiveState() throws Exception {
    create(onEquipment(in(1), in(2)));
    create(onEquipment(in(-1), in(1)));
    String cancelled = create(onEquipment(in(5), in(6)));
    mockMvc
        .perform(
            post(BASE + "/" + cancelled + "/annulation").header("Authorization", "Bearer " + noc))
        .andExpect(status().isOk());

    for (var expected : List.of("PLANNED", "IN_PROGRESS", "CANCELLED")) {
      mockMvc
          .perform(
              get(BASE)
                  .param("equipmentId", equipment.getId().toString())
                  .param("state", expected)
                  .header("Authorization", "Bearer " + viewer))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.page.totalElements").value(1))
          .andExpect(jsonPath("$.content[0].state").value(expected));
    }
    mockMvc
        .perform(
            get(BASE)
                .param("equipmentId", equipment.getId().toString())
                .header("Authorization", "Bearer " + viewer))
        .andExpect(jsonPath("$.page.totalElements").value(3));
  }

  @Test
  void cancellingStopsTheMaintenanceAndCannotBeRepeated() throws Exception {
    String id = create(onEquipment(in(-1), in(2)));

    mockMvc
        .perform(post(BASE + "/" + id + "/annulation").header("Authorization", "Bearer " + noc))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("CANCELLED"))
        .andExpect(jsonPath("$.cancelledAt").isNotEmpty());
    mockMvc
        .perform(post(BASE + "/" + id + "/annulation").header("Authorization", "Bearer " + noc))
        .andExpect(status().isConflict());
    mockMvc
        .perform(post(BASE + "/" + id + "/annulation").header("Authorization", "Bearer " + viewer))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            post(BASE + "/" + UUID.randomUUID() + "/annulation")
                .header("Authorization", "Bearer " + noc))
        .andExpect(status().isNotFound());
  }

  @Test
  void statesFollowTheClockWhenSynchronised() throws Exception {
    String id = create(onEquipment(in(1), in(2)));
    em.flush();
    jdbc.update(
        "UPDATE maintenance SET start_at = now() - interval '2 hours',"
            + " end_at = now() + interval '1 hour' WHERE id = ?",
        UUID.fromString(id));
    em.clear();

    assertThat(maintenanceService.syncStates()).isEqualTo(1);
    em.flush();
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM maintenance WHERE id = ?", String.class, UUID.fromString(id)))
        .isEqualTo("IN_PROGRESS");

    jdbc.update(
        "UPDATE maintenance SET end_at = now() - interval '1 minute' WHERE id = ?",
        UUID.fromString(id));
    em.clear();
    assertThat(maintenanceService.syncStates()).isEqualTo(1);
    em.flush();
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM maintenance WHERE id = ?", String.class, UUID.fromString(id)))
        .isEqualTo("COMPLETED");
  }

  @Test
  void aPlannedMaintenanceSilencesTheNotificationOfItsEquipmentAlerts() throws Exception {
    var rule =
        new AlertRule(
            "mnt-" + UUID.randomUUID(),
            "cpu.usage",
            ComparisonOperator.GREATER_THAN,
            new BigDecimal("90"),
            Duration.ZERO,
            Severity.CRITICAL);
    rule = rules.save(rule);
    scopes.save(new RuleEquipmentScope(rule, equipment));
    create(onEquipment(in(-1), in(1)));

    ingestion.ingest(
        equipment.getId(),
        List.of(
            new MeasureInput(
                "cpu.usage",
                new BigDecimal("97"),
                "%",
                null,
                Instant.now().minus(1, ChronoUnit.MINUTES))));

    var alert =
        alerts
            .findByEquipmentIdOrderByTriggeredAtDesc(equipment.getId(), Pageable.unpaged())
            .getContent()
            .get(0);
    assertThat(events.findByAlertIdOrderByOccurredAtAsc(alert.getId()).get(0).getComment())
        .contains("Maintenance");
  }
}

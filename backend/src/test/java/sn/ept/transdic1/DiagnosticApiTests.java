package sn.ept.transdic1;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.access.domain.SiteAccess;
import sn.ept.transdic1.access.repository.SiteAccessRepository;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.EquipmentStatus;
import sn.ept.transdic1.inventory.domain.EquipmentType;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.SiteRepository;
import sn.ept.transdic1.security.domain.AppUser;
import sn.ept.transdic1.security.domain.Role;
import sn.ept.transdic1.security.repository.UserRepository;

/** GET /api/v1/alertes/{id}/diagnostic : tous les rôles, périmètre par site, 404 et 401. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DiagnosticApiTests {
  private static final String PASSWORD = "Mot-de-passe-test-123";

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private PasswordEncoder encoder;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private SiteAccessRepository access;
  @Autowired private JdbcTemplate jdbc;

  private final String suffix = UUID.randomUUID().toString().substring(0, 8);
  private UUID alertA;
  private UUID alertB;

  @BeforeEach
  void data() {
    Site siteA = sites.saveAndFlush(new Site("DPA-" + suffix, "Site A " + suffix));
    Site siteB = sites.saveAndFlush(new Site("DPB-" + suffix, "Site B " + suffix));
    alertA = collectionAlert(equipment("A", siteA, 1));
    alertB = collectionAlert(equipment("B", siteB, 2));
    user("dp-view-", Role.VIEWER);
    var limited = user("dp-noc-", Role.NOC_OPERATOR);
    access.saveAndFlush(new SiteAccess(limited, siteA, Instant.now()));
  }

  @Test
  void unLecteurObtientLeDiagnosticAvecSonResume() throws Exception {
    String token = login("dp-view-" + suffix);
    mockMvc
        .perform(
            get("/api/v1/alertes/" + alertA + "/diagnostic")
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.alerteId").value(alertA.toString()))
        .andExpect(jsonPath("$.resume", Matchers.containsString("est injoignable")))
        .andExpect(jsonPath("$.fenetreMinutes").value(30))
        .andExpect(jsonPath("$.impact.racine").value(true))
        .andExpect(jsonPath("$.causesProbables[0].source").value("SILENCE_COLLECTE"))
        .andExpect(jsonPath("$.causesProbables[0].raisons").isNotEmpty())
        .andExpect(jsonPath("$.pistes.length()", Matchers.greaterThanOrEqualTo(2)))
        .andExpect(jsonPath("$.incidentsSimilaires.nombre").value(0));
  }

  @Test
  void unCompteLimiteRecoitUn404HorsDeSonPerimetre() throws Exception {
    String token = login("dp-noc-" + suffix);
    mockMvc
        .perform(
            get("/api/v1/alertes/" + alertA + "/diagnostic")
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            get("/api/v1/alertes/" + alertB + "/diagnostic")
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(
            get("/api/v1/alertes/" + UUID.randomUUID() + "/diagnostic")
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isNotFound());
  }

  @Test
  void sansJetonLeDiagnosticEstRefuse() throws Exception {
    mockMvc
        .perform(get("/api/v1/alertes/" + alertA + "/diagnostic"))
        .andExpect(status().isUnauthorized());
  }

  private Equipment equipment(String name, Site site, int host) {
    return equipments.saveAndFlush(
        new Equipment(
            "DP" + name + "-" + suffix,
            "Équipement " + name + " " + suffix,
            "198.18." + (Math.floorMod(suffix.hashCode(), 200) + 20) + "." + host,
            EquipmentType.ROUTER,
            EquipmentStatus.UP,
            site));
  }

  private UUID collectionAlert(Equipment equipment) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO alert(id, equipment_id, occurrence_key, type, state, severity, message,"
            + " triggered_at) VALUES (?, ?, ?, 'COLLECTION', 'OPEN', 'CRITICAL', 'Plus de réponse', ?)",
        id,
        equipment.getId(),
        "dp-" + id,
        Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)));
    return id;
  }

  private AppUser user(String prefix, Role role) {
    return users.saveAndFlush(
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
}

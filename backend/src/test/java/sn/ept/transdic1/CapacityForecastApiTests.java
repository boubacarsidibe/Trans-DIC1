package sn.ept.transdic1;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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
 * API de prévision de saturation : lecture seule, ouverte à tous les rôles, limitée au périmètre de
 * sites (ADR 0018) et sans effet sur les alertes.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class CapacityForecastApiTests {
  private static final String PASSWORD = "Mot-de-passe-test-123";

  @Autowired private MockMvc mockMvc;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private UserRepository users;
  @Autowired private SiteAccessRepository access;
  @Autowired private PasswordEncoder encoder;
  @Autowired private JdbcTemplate jdbc;

  private final String suffix = UUID.randomUUID().toString().substring(0, 8);
  private Site siteA;
  private Site siteB;
  private Equipment equipmentA;
  private Equipment equipmentB;

  @BeforeEach
  void data() {
    siteA = sites.save(new Site("PVA-" + suffix, "Site A " + suffix));
    siteB = sites.save(new Site("PVB-" + suffix, "Site B " + suffix));
    equipmentA = equipments.save(equipment("A", siteA, 21));
    equipmentB = equipments.save(equipment("B", siteB, 22));
    equipments.flush();
    // disk.usage : 76 % maintenant, +2 points par jour ; memory.usage : seulement 1 jour mesuré.
    Instant lastHour = Instant.now().truncatedTo(ChronoUnit.HOURS).minus(Duration.ofHours(1));
    Instant now = Instant.now();
    for (int h = 0; h <= 14 * 24; h++) {
      Instant at = lastHour.minus(Duration.ofHours(h));
      double days = Duration.between(now, at).toMillis() / 86_400_000.0;
      insert(equipmentA, "disk.usage", at, 76 + 2 * days);
      if (h <= 24) {
        insert(equipmentA, "memory.usage", at, 40);
      }
      insert(equipmentA, "cpu.usage", at, 30 + days);
    }
  }

  private void insert(Equipment equipment, String key, Instant at, double value) {
    jdbc.update(
        "INSERT INTO metric_hourly(equipment_id, metric_key, hour_start, unit, sample_count,"
            + " min_value, max_value, avg_value) VALUES (?, ?, ?, '%', 12, ?, ?, ?)",
        equipment.getId(), key, Timestamp.from(at), value, value, value);
  }

  private Equipment equipment(String name, Site site, int host) {
    return new Equipment(
        "PV" + name + "-" + suffix,
        "Équipement " + name + " " + suffix,
        "198.51." + (Math.floorMod(suffix.hashCode(), 200) + 20) + "." + host,
        EquipmentType.SERVER,
        EquipmentStatus.UP,
        site);
  }

  private String url(Equipment equipment) {
    return "/api/v1/equipements/" + equipment.getId() + "/previsions";
  }

  @Test
  @WithMockUser(roles = "VIEWER")
  void unLecteurObtientLaPrevisionDeChaqueIndicateurSurveille() throws Exception {
    mockMvc
        .perform(get(url(equipmentA)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].key").value("disk.usage"))
        .andExpect(jsonPath("$[0].unit").value("%"))
        .andExpect(jsonPath("$[0].status").value("OK"))
        // Les jours partent de la dernière mesure (1 à 2 h avant maintenant) : 12,0 à 12,1.
        .andExpect(jsonPath("$[0].daysToLimit").value(org.hamcrest.Matchers.closeTo(12.0, 0.15)))
        .andExpect(jsonPath("$[0].slopePerDay").value(2.0))
        .andExpect(jsonPath("$[0].r2").value(1.0))
        .andExpect(jsonPath("$[0].confidence").value("haute"))
        .andExpect(jsonPath("$[0].limit").value(100.0))
        .andExpect(jsonPath("$[0].windowDays").value(14))
        .andExpect(jsonPath("$[0].minSpanDays").value(3))
        .andExpect(jsonPath("$[0].points").value(14 * 24 - 1))
        .andExpect(jsonPath("$[0].projectedAt").isNotEmpty())
        .andExpect(jsonPath("$[0].current").isNumber())
        .andExpect(jsonPath("$[1].key").value("memory.usage"))
        .andExpect(jsonPath("$[1].status").value("NOT_ENOUGH_DATA"))
        .andExpect(jsonPath("$[1].daysToLimit").doesNotExist());
  }

  @Test
  @WithMockUser(roles = "VIEWER")
  void laLectureNOuvreAucuneAlerte() throws Exception {
    mockMvc.perform(get(url(equipmentA))).andExpect(status().isOk());

    org.assertj.core.api.Assertions.assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM alert WHERE equipment_id = ?",
                Integer.class,
                equipmentA.getId()))
        .isZero();
  }

  @Test
  @WithMockUser(roles = "VIEWER")
  void unEquipementSansMesureRenvoieUneListeVideEtUnInconnuUn404() throws Exception {
    mockMvc
        .perform(get(url(equipmentB)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
    mockMvc
        .perform(get("/api/v1/equipements/" + UUID.randomUUID() + "/previsions"))
        .andExpect(status().isNotFound());
  }

  @Test
  void unOperateurLimiteAuSiteBNeVoitPasLEquipementDuSiteA() throws Exception {
    AppUser limited =
        users.saveAndFlush(
            new AppUser(
                "pv-noc-" + suffix,
                "pv-noc-" + suffix + "@example.test",
                encoder.encode(PASSWORD),
                Role.NOC_OPERATOR));
    access.saveAndFlush(new SiteAccess(limited, siteB, Instant.now()));
    String token = login("pv-noc-" + suffix);

    mockMvc
        .perform(get(url(equipmentA)).header("Authorization", "Bearer " + token))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(get(url(equipmentB)).header("Authorization", "Bearer " + token))
        .andExpect(status().isOk());
  }

  @Test
  void lAuthentificationEstRequise() throws Exception {
    mockMvc.perform(get(url(equipmentA))).andExpect(status().isUnauthorized());
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

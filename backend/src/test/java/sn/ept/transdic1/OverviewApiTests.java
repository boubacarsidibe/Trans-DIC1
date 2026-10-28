package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
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

/** Chiffres clés du tableau de bord, comparés à un état de départ (la base est partagée). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class OverviewApiTests {
  private static final String PASSWORD = "Mot-de-passe-test-123";

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private PasswordEncoder encoder;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private AlertRepository alerts;

  private String viewer;

  @BeforeEach
  void viewer() throws Exception {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    users.save(
        new AppUser(
            "vwr-" + suffix,
            "vwr-" + suffix + "@example.test",
            encoder.encode(PASSWORD),
            Role.VIEWER));
    var body =
        mockMvc
            .perform(
                post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"username\":\"vwr-" + suffix + "\",\"password\":\"" + PASSWORD + "\"}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    viewer = JsonPath.read(body, "$.accessToken");
  }

  private String overview() throws Exception {
    return mockMvc
        .perform(get("/api/v1/vue-ensemble").header("Authorization", "Bearer " + viewer))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  private static int number(String json, String path) {
    return ((Number) JsonPath.read(json, path)).intValue();
  }

  @Test
  void requiresAuthentication() throws Exception {
    mockMvc.perform(get("/api/v1/vue-ensemble")).andExpect(status().isUnauthorized());
  }

  @Test
  void listsEveryStatusAndSeverityEvenWhenEmpty() throws Exception {
    mockMvc
        .perform(get("/api/v1/vue-ensemble").header("Authorization", "Bearer " + viewer))
        .andExpect(jsonPath("$.equipments.byStatus.UP").exists())
        .andExpect(jsonPath("$.equipments.byStatus.DOWN").exists())
        .andExpect(jsonPath("$.equipments.byStatus.DEGRADED").exists())
        .andExpect(jsonPath("$.equipments.byStatus.MAINTENANCE").exists())
        .andExpect(jsonPath("$.equipments.byStatus.UNKNOWN").exists())
        .andExpect(jsonPath("$.alerts.activeBySeverity.CRITICAL").exists())
        .andExpect(jsonPath("$.alerts.activeBySeverity.WARNING").exists())
        .andExpect(jsonPath("$.alerts.activeBySeverity.INFO").exists())
        .andExpect(jsonPath("$.generatedAt").isNotEmpty());
  }

  @Test
  void countsFollowEquipmentsAndActiveAlerts() throws Exception {
    String before = overview();
    String unique = UUID.randomUUID().toString();
    Site site = sites.save(new Site("OVW-" + unique.substring(0, 8), "Site de synthèse"));
    Equipment down =
        equipments.save(
            new Equipment(
                "OVW-EQ-" + unique,
                "Switch en panne",
                "10.240."
                    + (Math.floorMod(unique.hashCode(), 250) + 1)
                    + "."
                    + (Math.floorMod(unique.substring(9).hashCode(), 250) + 1),
                EquipmentType.SWITCH,
                EquipmentStatus.DOWN,
                site));
    equipments.flush();
    alerts.save(alert(down, AlertState.OPEN, Severity.CRITICAL));
    alerts.flush();

    String after = overview();

    assertThat(number(after, "$.equipments.total"))
        .isEqualTo(number(before, "$.equipments.total") + 1);
    assertThat(number(after, "$.equipments.byStatus.DOWN"))
        .isEqualTo(number(before, "$.equipments.byStatus.DOWN") + 1);
    assertThat(number(after, "$.alerts.active")).isEqualTo(number(before, "$.alerts.active") + 1);
    assertThat(number(after, "$.alerts.open")).isEqualTo(number(before, "$.alerts.open") + 1);
    assertThat(number(after, "$.alerts.activeBySeverity.CRITICAL"))
        .isEqualTo(number(before, "$.alerts.activeBySeverity.CRITICAL") + 1);
  }

  private Alert alert(Equipment equipment, AlertState state, Severity severity) {
    return new Alert(
        equipment,
        "ovw-" + UUID.randomUUID(),
        AlertType.COLLECTION,
        state,
        severity,
        "Injoignable",
        Instant.now());
  }
}

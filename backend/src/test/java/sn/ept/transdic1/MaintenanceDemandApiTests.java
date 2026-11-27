package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
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

/**
 * Demande de maintenance (ADR 0058) : l'opérateur demande, l'administrateur valide ou refuse ; tout
 * est tracé dans l'audit et dans la chronologie de l'alerte d'origine.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class MaintenanceDemandApiTests {
  private static final String PASSWORD = "Mot-de-passe-test-123";
  private static final String BASE = "/api/v1/maintenances/demandes";

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private PasswordEncoder encoder;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private AlertRepository alerts;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private jakarta.persistence.EntityManager em;

  private Equipment equipment;
  private Alert alert;
  private String admin;
  private String noc;
  private String viewer;
  private String nocName;

  @BeforeEach
  void data() throws Exception {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    nocName = "mdn-noc-" + suffix;
    users.save(
        new AppUser(
            "mdn-adm-" + suffix,
            "mdn-adm-" + suffix + "@example.test",
            encoder.encode(PASSWORD),
            Role.ADMIN));
    users.save(
        new AppUser(
            nocName, nocName + "@example.test", encoder.encode(PASSWORD), Role.NOC_OPERATOR));
    users.save(
        new AppUser(
            "mdn-vwr-" + suffix,
            "mdn-vwr-" + suffix + "@example.test",
            encoder.encode(PASSWORD),
            Role.VIEWER));
    admin = login("mdn-adm-" + suffix);
    noc = login(nocName);
    viewer = login("mdn-vwr-" + suffix);
    String unique = UUID.randomUUID().toString();
    Site site = sites.save(new Site("MDN-" + suffix, "Site des demandes"));
    equipment =
        equipments.save(
            new Equipment(
                "MDN-EQ-" + unique,
                "Serveur de fichiers",
                "10.236."
                    + (Math.floorMod(unique.hashCode(), 250) + 1)
                    + "."
                    + (Math.floorMod(unique.substring(9).hashCode(), 250) + 1),
                EquipmentType.SERVER,
                EquipmentStatus.UP,
                site));
    alert =
        alerts.save(
            new Alert(
                equipment,
                "mdn:" + unique,
                AlertType.COLLECTION,
                AlertState.OPEN,
                Severity.CRITICAL,
                "Plus de réponse",
                Instant.now()));
    em.flush();
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

  private String demandBody() {
    return "{\"equipmentId\":\""
        + equipment.getId()
        + "\",\"alertId\":\""
        + alert.getId()
        + "\",\"startAt\":\""
        + in(1)
        + "\",\"endAt\":\""
        + in(2)
        + "\",\"reason\":\"Remplacement du disque\"}";
  }

  private ResultActions send(String path, String jwt, String body) throws Exception {
    var request = post(BASE + path).header("Authorization", "Bearer " + jwt);
    if (body != null) {
      request = request.contentType(MediaType.APPLICATION_JSON).content(body);
    }
    return mockMvc.perform(request);
  }

  private String demand() throws Exception {
    var body =
        send("", noc, demandBody())
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.state").value("PENDING"))
            .andExpect(jsonPath("$.requester").value(nocName))
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(body, "$.id");
  }

  private int count(String sql, Object... args) {
    em.flush();
    return jdbc.queryForObject(sql, Integer.class, args);
  }

  @Test
  void lOperateurDemandeEtLAdministrateurValide() throws Exception {
    String id = demand();
    // Une demande en attente ne planifie rien.
    assertThat(count("SELECT count(*) FROM maintenance WHERE equipment_id = ?", equipment.getId()))
        .isZero();
    mockMvc
        .perform(get(BASE).header("Authorization", "Bearer " + admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.id == '" + id + "')].state").value("PENDING"));

    send("/" + id + "/validation", admin, "{\"comment\":\"OK pour ce soir\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("APPROVED"))
        .andExpect(jsonPath("$.maintenanceId").isNotEmpty());
    assertThat(
            count(
                "SELECT count(*) FROM maintenance WHERE equipment_id = ? AND state = 'PLANNED'",
                equipment.getId()))
        .isEqualTo(1);
    send("/" + id + "/validation", admin, null).andExpect(status().isConflict());

    // Trace : audit des deux étapes, et chronologie de l'alerte d'origine.
    assertThat(
            count(
                "SELECT count(*) FROM audit_entry WHERE target_id = ? AND action IN"
                    + " ('MAINTENANCE_REQUESTED', 'MAINTENANCE_REQUEST_APPROVED')",
                UUID.fromString(id)))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForList(
                "SELECT comment FROM alert_event WHERE alert_id = ? AND type = 'COMMENTED'"
                    + " ORDER BY occurred_at",
                String.class,
                alert.getId()))
        .containsExactly(
            "Maintenance demandée : Remplacement du disque",
            "Maintenance validée : OK pour ce soir");
  }

  @Test
  void leRefusExigeUnMotifEtEstTrace() throws Exception {
    String id = demand();
    send("/" + id + "/refus", admin, "{\"comment\":\" \"}").andExpect(status().isBadRequest());
    send("/" + id + "/refus", admin, "{\"comment\":\"Période d'examens\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("REFUSED"))
        .andExpect(jsonPath("$.decisionComment").value("Période d'examens"));
    assertThat(
            count(
                "SELECT count(*) FROM audit_entry WHERE target_id = ? AND action ="
                    + " 'MAINTENANCE_REQUEST_REFUSED'",
                UUID.fromString(id)))
        .isEqualTo(1);
    assertThat(count("SELECT count(*) FROM maintenance WHERE equipment_id = ?", equipment.getId()))
        .isZero();
    // L'opérateur voit la décision dans ses demandes.
    mockMvc
        .perform(get(BASE).header("Authorization", "Bearer " + noc))
        .andExpect(jsonPath("$[0].state").value("REFUSED"));
  }

  @Test
  void seulLAdministrateurDecideEtLeLecteurNeDemandePas() throws Exception {
    String id = demand();
    send("/" + id + "/validation", noc, null).andExpect(status().isForbidden());
    send("/" + id + "/refus", noc, "{\"comment\":\"non\"}").andExpect(status().isForbidden());
    send("/" + id + "/validation", viewer, null).andExpect(status().isForbidden());
    send("", viewer, demandBody()).andExpect(status().isForbidden());
    mockMvc
        .perform(get(BASE).header("Authorization", "Bearer " + viewer))
        .andExpect(status().isForbidden());
  }
}

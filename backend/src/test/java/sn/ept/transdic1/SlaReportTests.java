package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
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
import sn.ept.transdic1.alerting.domain.AlertEvent;
import sn.ept.transdic1.alerting.domain.AlertEventType;
import sn.ept.transdic1.alerting.domain.AlertState;
import sn.ept.transdic1.alerting.domain.AlertType;
import sn.ept.transdic1.alerting.domain.Maintenance;
import sn.ept.transdic1.alerting.domain.MaintenanceState;
import sn.ept.transdic1.alerting.domain.ResolutionType;
import sn.ept.transdic1.alerting.domain.Severity;
import sn.ept.transdic1.alerting.repository.AlertEventRepository;
import sn.ept.transdic1.alerting.repository.AlertRepository;
import sn.ept.transdic1.alerting.repository.MaintenanceRepository;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.EquipmentStatus;
import sn.ept.transdic1.inventory.domain.EquipmentType;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.SiteRepository;
import sn.ept.transdic1.security.domain.AppUser;
import sn.ept.transdic1.security.domain.Role;
import sn.ept.transdic1.security.repository.UserRepository;

/** SLA par site : maintenances exclues, comparaison de périodes, cible configurable, PDF. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SlaReportTests {
  private static final String PASSWORD = "Mot-de-passe-test-123";
  // Dix jours : 864 000 secondes. Référence par défaut : les dix jours précédents.
  private static final Instant FROM = Instant.parse("2026-03-11T00:00:00Z");
  private static final Instant TO = Instant.parse("2026-03-21T00:00:00Z");

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private PasswordEncoder encoder;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private AlertRepository alerts;
  @Autowired private AlertEventRepository events;
  @Autowired private MaintenanceRepository maintenances;
  @Autowired private JdbcTemplate jdbc;

  private Site site;
  private AppUser planner;
  private String viewer;
  private String admin;
  private String suffix;

  @BeforeEach
  void data() throws Exception {
    // Seul le site du test compte : les équipements de démonstration ne sont plus supervisés.
    jdbc.update("UPDATE equipment SET monitoring_enabled = FALSE");
    suffix = UUID.randomUUID().toString().substring(0, 8);
    planner =
        users.save(
            new AppUser(
                "vwr-" + suffix,
                "vwr-" + suffix + "@example.test",
                encoder.encode(PASSWORD),
                Role.VIEWER));
    users.save(
        new AppUser(
            "adm-" + suffix,
            "adm-" + suffix + "@example.test",
            encoder.encode(PASSWORD),
            Role.ADMIN));
    viewer = login("vwr-" + suffix);
    admin = login("adm-" + suffix);
    site = new Site("SLA-" + suffix, "Site SLA");
    site.setSlaTarget(new BigDecimal("99.900"));
    site = sites.save(site);
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

  private Equipment equipment(String name) {
    String unique = UUID.randomUUID().toString();
    var created =
        equipments.save(
            new Equipment(
                "SLA-EQ-" + unique,
                name,
                "10.233."
                    + (Math.floorMod(unique.hashCode(), 250) + 1)
                    + "."
                    + (Math.floorMod(unique.substring(9).hashCode(), 250) + 1),
                EquipmentType.SERVER,
                EquipmentStatus.UP,
                site));
    equipments.flush();
    return created;
  }

  private void outage(Equipment equipment, Instant start, Instant end) {
    var alert =
        new Alert(
            equipment,
            "sla-" + UUID.randomUUID(),
            AlertType.COLLECTION,
            AlertState.RESOLVED,
            Severity.CRITICAL,
            "Injoignable",
            start);
    alert.setResolvedAt(end);
    alert.setResolutionType(ResolutionType.AUTOMATIC);
    alerts.saveAndFlush(alert);
    events.save(new AlertEvent(alert, AlertEventType.OPENED, start));
    events.save(new AlertEvent(alert, AlertEventType.RESOLVED, end));
    events.flush();
  }

  private ResultActions sla(String extra) throws Exception {
    return mockMvc.perform(
        get("/api/v1/rapports/sla?from=" + FROM + "&to=" + TO + extra)
            .header("Authorization", "Bearer " + viewer));
  }

  @Test
  void lesMaintenancesSontExcluesDuTempsAttenduEtDeLIndisponibilite() throws Exception {
    Equipment server = equipment("Serveur SLA");
    Instant start = FROM.plus(Duration.ofDays(2));
    outage(server, start, start.plus(Duration.ofDays(1)));
    // Maintenance planifiée sur la première moitié de la coupure.
    var window =
        new Maintenance(
            planner,
            start,
            start.plus(Duration.ofHours(12)),
            "Changement de disque",
            MaintenanceState.COMPLETED);
    window.setEquipment(server);
    maintenances.saveAndFlush(window);

    // Attendu : 864 000 − 43 200 = 820 800 s ; indisponible : 86 400 − 43 200 = 43 200 s.
    // Disponibilité = 777 600 / 820 800 = 94,737 %.
    sla("")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sites.length()").value(1))
        .andExpect(jsonPath("$.sites[0].siteName").value("Site SLA"))
        .andExpect(jsonPath("$.sites[0].target").value(99.9))
        .andExpect(jsonPath("$.sites[0].availability").value(94.737))
        .andExpect(jsonPath("$.sites[0].previousAvailability").value(100.0))
        .andExpect(jsonPath("$.sites[0].delta").value(-5.263))
        .andExpect(jsonPath("$.sites[0].met").value(false))
        .andExpect(jsonPath("$.sites[0].degraded").value(true))
        .andExpect(jsonPath("$.sites[0].equipments[0].downtimeSeconds").value(43200))
        .andExpect(jsonPath("$.sites[0].equipments[0].maintenanceSeconds").value(43200))
        .andExpect(
            jsonPath("$.method")
                .value(org.hamcrest.Matchers.containsString("maintenances planifiées")));
  }

  @Test
  void leSiteAgregeSesEquipementsEtLaReferenceEstChoisie() throws Exception {
    Equipment a = equipment("A");
    equipment("B");
    outage(
        a, FROM.plus(Duration.ofDays(1)), FROM.plus(Duration.ofDays(1)).plus(Duration.ofHours(24)));
    Instant refFrom = FROM.minus(Duration.ofDays(30));
    Instant refTo = FROM.minus(Duration.ofDays(20));
    outage(
        a,
        refFrom.plus(Duration.ofDays(1)),
        refFrom.plus(Duration.ofDays(1)).plus(Duration.ofHours(48)));

    // Site : 2 × 864 000 attendus, 86 400 indisponibles → 95,000 % ; référence : 172 800 → 90,000
    // %.
    sla("&compareFrom=" + refFrom + "&compareTo=" + refTo)
        .andExpect(jsonPath("$.compareFrom").value(refFrom.toString()))
        .andExpect(jsonPath("$.sites[0].availability").value(95.0))
        .andExpect(jsonPath("$.sites[0].previousAvailability").value(90.0))
        .andExpect(jsonPath("$.sites[0].delta").value(5.0))
        .andExpect(jsonPath("$.sites[0].degraded").value(false));
  }

  @Test
  void laCibleSeRegleSurLeSiteEtLeRapportLaRespecte() throws Exception {
    equipment("Sans coupure");
    mockMvc
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                    "/api/v1/sites/" + site.getId())
                .header("Authorization", "Bearer " + admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Site SLA\",\"slaTarget\":99.95}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.slaTarget").value(99.95));
    mockMvc
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                    "/api/v1/sites/" + site.getId())
                .header("Authorization", "Bearer " + admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Site SLA\",\"slaTarget\":120}"))
        .andExpect(status().isBadRequest());

    sla("")
        .andExpect(jsonPath("$.sites[0].target").value(99.95))
        .andExpect(jsonPath("$.sites[0].met").value(true));
  }

  @Test
  void lePdfContientLaSyntheseEtLaMethode() throws Exception {
    equipment("Serveur PDF");
    byte[] pdf =
        sla("&format=pdf")
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
    var reader = new org.openpdf.text.pdf.PdfReader(pdf);
    var extractor = new org.openpdf.text.pdf.parser.PdfTextExtractor(reader);
    var text = new StringBuilder();
    for (int page = 1; page <= reader.getNumberOfPages(); page++) {
      text.append(extractor.getTextFromPage(page));
    }
    reader.close();
    assertThat(text)
        .contains("SLA par site")
        .contains("Site SLA")
        .contains("Méthode de calcul")
        .contains("Respecté");
  }

  @Test
  void uneReferenceInvalideEstRefusee() throws Exception {
    sla("&compareFrom=" + TO + "&compareTo=" + FROM).andExpect(status().isBadRequest());
  }
}

package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
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
import sn.ept.transdic1.alerting.domain.ResolutionType;
import sn.ept.transdic1.alerting.domain.Severity;
import sn.ept.transdic1.alerting.repository.AlertEventRepository;
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

/** Rapports de disponibilité et d'incidents : calcul des coupures, filtres, CSV, droits. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ReportApiTests {
  private static final String PASSWORD = "Mot-de-passe-test-123";
  // Dix jours exactement : 864 000 secondes.
  private static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");
  private static final Instant TO = Instant.parse("2026-01-11T00:00:00Z");

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private PasswordEncoder encoder;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private AlertRepository alerts;
  @Autowired private AlertEventRepository events;

  private Site site;
  private String viewer;
  private int counter;

  @BeforeEach
  void data() throws Exception {
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
    site = sites.save(new Site("RPT-" + suffix, "Site des rapports"));
  }

  private Equipment equipment(String name) {
    String unique = UUID.randomUUID().toString();
    var created =
        equipments.save(
            new Equipment(
                "RPT-EQ-" + unique,
                name,
                "10.234."
                    + (++counter + Math.floorMod(unique.hashCode(), 200))
                    + "."
                    + (Math.floorMod(unique.substring(9).hashCode(), 250) + 1),
                EquipmentType.SERVER,
                EquipmentStatus.UP,
                site));
    equipments.flush();
    return created;
  }

  /** Une alerte de collecte avec ses événements, dans l'ordre : ouverture, résolution... */
  private Alert collectionAlert(Equipment equipment, AlertState state, Object... timeline) {
    Instant first = (Instant) timeline[0];
    var alert =
        new Alert(
            equipment,
            "rpt-" + UUID.randomUUID(),
            AlertType.COLLECTION,
            state,
            Severity.CRITICAL,
            "Injoignable",
            first);
    Instant lastResolved = null;
    for (int i = 0; i < timeline.length; i += 2) {
      if (timeline[i + 1] == AlertEventType.RESOLVED) {
        lastResolved = (Instant) timeline[i];
      }
    }
    if (state == AlertState.RESOLVED || state == AlertState.ARCHIVED) {
      alert.setResolvedAt(lastResolved);
      alert.setResolutionType(ResolutionType.AUTOMATIC);
    }
    alerts.saveAndFlush(alert);
    for (int i = 0; i < timeline.length; i += 2) {
      events.save(new AlertEvent(alert, (AlertEventType) timeline[i + 1], (Instant) timeline[i]));
    }
    events.flush();
    return alert;
  }

  private ResultActions availability(String extra) throws Exception {
    return mockMvc.perform(
        get("/api/v1/rapports/disponibilite?from="
                + FROM
                + "&to="
                + TO
                + "&siteId="
                + site.getId()
                + extra)
            .header("Authorization", "Bearer " + viewer));
  }

  private ResultActions incidents(String extra) throws Exception {
    return mockMvc.perform(
        get("/api/v1/rapports/incidents?from="
                + FROM
                + "&to="
                + TO
                + "&siteId="
                + site.getId()
                + extra)
            .header("Authorization", "Bearer " + viewer));
  }

  @Test
  void availabilityFollowsEachOutageOfEachEquipment() throws Exception {
    equipment("Aucune coupure");
    var oneDay = equipment("Une journée");
    collectionAlert(
        oneDay,
        AlertState.RESOLVED,
        Instant.parse("2026-01-02T00:00:00Z"),
        AlertEventType.OPENED,
        Instant.parse("2026-01-03T00:00:00Z"),
        AlertEventType.RESOLVED);
    var reopened = equipment("Rouverte");
    collectionAlert(
        reopened,
        AlertState.RESOLVED,
        Instant.parse("2026-01-02T00:00:00Z"),
        AlertEventType.OPENED,
        Instant.parse("2026-01-02T01:00:00Z"),
        AlertEventType.RESOLVED,
        Instant.parse("2026-01-05T00:00:00Z"),
        AlertEventType.OPENED,
        Instant.parse("2026-01-05T02:00:00Z"),
        AlertEventType.RESOLVED);

    availability("")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.periodSeconds").value(864000))
        .andExpect(jsonPath("$.rows.length()").value(3))
        .andExpect(
            jsonPath("$.rows[?(@.equipmentName=='Aucune coupure')].availabilityPercent")
                .value(100.0))
        .andExpect(
            jsonPath("$.rows[?(@.equipmentName=='Une journée')].downtimeSeconds").value(86400))
        .andExpect(
            jsonPath("$.rows[?(@.equipmentName=='Une journée')].availabilityPercent").value(90.0))
        .andExpect(jsonPath("$.rows[?(@.equipmentName=='Rouverte')].outages").value(2))
        .andExpect(jsonPath("$.rows[?(@.equipmentName=='Rouverte')].downtimeSeconds").value(10800))
        .andExpect(
            jsonPath("$.rows[?(@.equipmentName=='Rouverte')].availabilityPercent").value(98.75))
        // (864000*3 - 86400 - 10800) / (864000*3) = 96.25
        .andExpect(jsonPath("$.overallAvailabilityPercent").value(96.25));
  }

  @Test
  void outagesAreClippedToThePeriodAndAnOpenOneRunsToItsEnd() throws Exception {
    var spanning = equipment("A cheval");
    collectionAlert(
        spanning,
        AlertState.RESOLVED,
        Instant.parse("2025-12-31T00:00:00Z"),
        AlertEventType.OPENED,
        Instant.parse("2026-01-01T12:00:00Z"),
        AlertEventType.RESOLVED);
    var open = equipment("Toujours ouverte");
    collectionAlert(
        open, AlertState.OPEN, Instant.parse("2026-01-10T00:00:00Z"), AlertEventType.OPENED);

    availability("")
        .andExpect(jsonPath("$.rows[?(@.equipmentName=='A cheval')].downtimeSeconds").value(43200))
        .andExpect(
            jsonPath("$.rows[?(@.equipmentName=='Toujours ouverte')].downtimeSeconds").value(86400))
        .andExpect(
            jsonPath("$.rows[?(@.equipmentName=='Toujours ouverte')].availabilityPercent")
                .value(90.0));
  }

  @Test
  void anOutageThatNeverEndedBeforeTheReportIsCountedUpToNowAtMost() throws Exception {
    var open = equipment("Ouverte depuis deux jours");
    Instant openedAt = Instant.now().minus(2, ChronoUnit.DAYS);
    collectionAlert(open, AlertState.OPEN, openedAt, AlertEventType.OPENED);
    Instant from = Instant.now().minus(10, ChronoUnit.DAYS);
    Instant to = Instant.now().plus(10, ChronoUnit.DAYS);

    var body =
        mockMvc
            .perform(
                get("/api/v1/rapports/disponibilite?from="
                        + from
                        + "&to="
                        + to
                        + "&siteId="
                        + site.getId())
                    .header("Authorization", "Bearer " + viewer))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    // Deux jours, pas douze : la partie future de la période n'est pas de l'indisponibilité.
    int downtime = ((Number) JsonPath.read(body, "$.rows[0].downtimeSeconds")).intValue();
    assertThat(downtime).isBetween(172_790, 172_900);
  }

  @Test
  void aDeletedAlertStopsItsOutage() throws Exception {
    var deleted = equipment("Alerte supprimée");
    collectionAlert(
        deleted,
        AlertState.DELETED,
        Instant.parse("2026-01-02T00:00:00Z"),
        AlertEventType.OPENED,
        Instant.parse("2026-01-02T06:00:00Z"),
        AlertEventType.DELETED);

    availability("").andExpect(jsonPath("$.rows[0].downtimeSeconds").value(21600));
  }

  @Test
  void incidentsSummariseVolumesResolutionTimeAndWorstEquipments() throws Exception {
    var noisy = equipment("Très bavard");
    var quiet = equipment("Discret");
    // 3 alertes pour le premier (résolues en 1 h, 3 h, et une encore ouverte), 1 pour le second (2
    // h).
    collectionAlert(
        noisy,
        AlertState.RESOLVED,
        Instant.parse("2026-01-02T00:00:00Z"),
        AlertEventType.OPENED,
        Instant.parse("2026-01-02T01:00:00Z"),
        AlertEventType.RESOLVED);
    collectionAlert(
        noisy,
        AlertState.ARCHIVED,
        Instant.parse("2026-01-03T00:00:00Z"),
        AlertEventType.OPENED,
        Instant.parse("2026-01-03T03:00:00Z"),
        AlertEventType.RESOLVED);
    collectionAlert(
        noisy, AlertState.OPEN, Instant.parse("2026-01-04T00:00:00Z"), AlertEventType.OPENED);
    collectionAlert(
        quiet,
        AlertState.RESOLVED,
        Instant.parse("2026-01-05T00:00:00Z"),
        AlertEventType.OPENED,
        Instant.parse("2026-01-05T02:00:00Z"),
        AlertEventType.RESOLVED);
    // Hors période : ne compte pas.
    collectionAlert(
        quiet,
        AlertState.RESOLVED,
        Instant.parse("2025-12-01T00:00:00Z"),
        AlertEventType.OPENED,
        Instant.parse("2025-12-01T01:00:00Z"),
        AlertEventType.RESOLVED);

    incidents("")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(4))
        .andExpect(jsonPath("$.resolved").value(3))
        // (1 h + 3 h + 2 h) / 3 = 2 h
        .andExpect(jsonPath("$.meanTimeToResolveSeconds").value(7200))
        .andExpect(jsonPath("$.bySeverity.CRITICAL").value(4))
        .andExpect(jsonPath("$.bySeverity.WARNING").value(0))
        .andExpect(jsonPath("$.topEquipments[0].equipmentName").value("Très bavard"))
        .andExpect(jsonPath("$.topEquipments[0].count").value(3))
        .andExpect(jsonPath("$.topEquipments[1].equipmentName").value("Discret"))
        .andExpect(jsonPath("$.incidents.length()").value(4))
        .andExpect(jsonPath("$.incidents[0].triggeredAt").value("2026-01-05T00:00:00Z"));
  }

  @Test
  void incidentsIgnoreDeletedAlertsAndOtherSites() throws Exception {
    var here = equipment("Ici");
    collectionAlert(
        here, AlertState.DELETED, Instant.parse("2026-01-02T00:00:00Z"), AlertEventType.OPENED);
    Site other =
        sites.save(new Site("OTH-" + UUID.randomUUID().toString().substring(0, 8), "Autre site"));
    String unique = UUID.randomUUID().toString();
    var elsewhere =
        equipments.save(
            new Equipment(
                "RPT-OT-" + unique,
                "Ailleurs",
                "10.233."
                    + (Math.floorMod(unique.hashCode(), 250) + 1)
                    + "."
                    + (Math.floorMod(unique.substring(9).hashCode(), 250) + 1),
                EquipmentType.SERVER,
                EquipmentStatus.UP,
                other));
    equipments.flush();
    collectionAlert(
        elsewhere, AlertState.OPEN, Instant.parse("2026-01-03T00:00:00Z"), AlertEventType.OPENED);

    incidents("")
        .andExpect(jsonPath("$.total").value(0))
        .andExpect(jsonPath("$.meanTimeToResolveSeconds").doesNotExist());
  }

  @Test
  void csvExportsAreSpreadsheetReadyAndNeutraliseFormulas() throws Exception {
    var evil = equipment("=HYPERLINK(\"http://pirate.example\";\"clic\")");
    collectionAlert(
        evil,
        AlertState.RESOLVED,
        Instant.parse("2026-01-02T00:00:00Z"),
        AlertEventType.OPENED,
        Instant.parse("2026-01-02T12:00:00Z"),
        AlertEventType.RESOLVED);

    var response =
        availability("&format=csv")
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("text/csv"))
            .andExpect(
                header()
                    .string(
                        "Content-Disposition",
                        org.hamcrest.Matchers.containsString("disponibilite.csv")))
            .andReturn()
            .getResponse();
    String csv = response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

    assertThat(csv).startsWith("﻿");
    assertThat(csv).contains("Équipement;Site;Coupures;Indisponibilité (s);Disponibilité (%)");
    // Une cellule qui commence par = serait exécutée par le tableur : elle est préfixée et citée.
    assertThat(csv).contains("\"'=HYPERLINK(");
    assertThat(csv).doesNotContain(";=HYPERLINK").doesNotContain("\n=HYPERLINK");
    // Virgule décimale pour un tableur en français : 43 200 s sur 864 000 s = 95,000 %.
    assertThat(csv).contains("43200;95,000");

    incidents("&format=csv")
        .andExpect(status().isOk())
        .andExpect(
            header()
                .string(
                    "Content-Disposition", org.hamcrest.Matchers.containsString("incidents.csv")));
  }

  @Test
  void pdfExportsAreReadableAndStatePeriodAndGenerationDate() throws Exception {
    var named = equipment("Routeur PDF");
    collectionAlert(
        named,
        AlertState.RESOLVED,
        Instant.parse("2026-01-02T00:00:00Z"),
        AlertEventType.OPENED,
        Instant.parse("2026-01-02T12:00:00Z"),
        AlertEventType.RESOLVED);

    var response =
        availability("&format=pdf")
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("application/pdf"))
            .andExpect(
                header()
                    .string(
                        "Content-Disposition",
                        org.hamcrest.Matchers.containsString("disponibilite.pdf")))
            .andReturn()
            .getResponse();
    byte[] bytes = response.getContentAsByteArray();
    assertThat(new String(bytes, 0, 5, java.nio.charset.StandardCharsets.US_ASCII))
        .isEqualTo("%PDF-");
    String text = pdfText(bytes);
    assertThat(text).contains("Rapport de disponibilité").contains("Période : du");
    assertThat(text).contains("Généré le").contains("Routeur PDF").contains("95,000 %");

    String incidentsText =
        pdfText(
            incidents("&format=pdf")
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/pdf"))
                .andReturn()
                .getResponse()
                .getContentAsByteArray());
    assertThat(incidentsText).contains("Rapport d'incidents").contains("Liste des alertes");
  }

  private static String pdfText(byte[] pdf) throws java.io.IOException {
    var reader = new org.openpdf.text.pdf.PdfReader(pdf);
    var extractor = new org.openpdf.text.pdf.parser.PdfTextExtractor(reader);
    var text = new StringBuilder();
    for (int page = 1; page <= reader.getNumberOfPages(); page++) {
      text.append(extractor.getTextFromPage(page)).append('\n');
    }
    reader.close();
    return text.toString();
  }

  @Test
  void invalidPeriodsAreRejected() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/rapports/disponibilite?from=" + TO + "&to=" + FROM)
                .header("Authorization", "Bearer " + viewer))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            get("/api/v1/rapports/incidents?from="
                    + FROM
                    + "&to="
                    + FROM.plus(Duration.ofDays(401)))
                .header("Authorization", "Bearer " + viewer))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            get("/api/v1/rapports/incidents?from=pas-une-date&to=" + TO)
                .header("Authorization", "Bearer " + viewer))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(get("/api/v1/rapports/incidents").header("Authorization", "Bearer " + viewer))
        .andExpect(status().isBadRequest());
  }

  @Test
  void reportsRequireAuthentication() throws Exception {
    mockMvc
        .perform(get("/api/v1/rapports/disponibilite?from=" + FROM + "&to=" + TO))
        .andExpect(status().isUnauthorized());
  }
}

package sn.ept.transdic1.statuspage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static sn.ept.transdic1.statuspage.StatusPageTestSupport.BASE;
import static sn.ept.transdic1.statuspage.StatusPageTestSupport.PUBLIC;
import static sn.ept.transdic1.statuspage.StatusPageTestSupport.addComponent;
import static sn.ept.transdic1.statuspage.StatusPageTestSupport.auth;
import static sn.ept.transdic1.statuspage.StatusPageTestSupport.createIncident;
import static sn.ept.transdic1.statuspage.StatusPageTestSupport.createPage;
import static sn.ept.transdic1.statuspage.StatusPageTestSupport.from;
import static sn.ept.transdic1.statuspage.StatusPageTestSupport.login;
import static sn.ept.transdic1.statuspage.StatusPageTestSupport.randomAddress;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.regex.Pattern;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.inventory.domain.Equipment;
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
 * Lecture publique sans connexion d'une page de statut (ADR 0026) : liste blanche stricte (aucune
 * donnée technique ne fuit), même 404 pour une page inconnue, interne ou inactive, en-têtes de
 * cache et de sécurité, lecture seule, limitation par adresse IP et cache de 30 s.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PublicStatusPageTests {
  private static final Pattern UUID_PATTERN =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private PasswordEncoder encoder;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private MonitoredServiceRepository services;
  @Autowired private JdbcTemplate jdbc;

  private final String suffix = StatusPageTestSupport.suffix();
  private final String client = randomAddress();
  private Site site;
  private Equipment server;
  private MonitoredService probe;
  private AppUser admin;
  private String token;

  @BeforeEach
  void data() throws Exception {
    site = sites.saveAndFlush(new Site("SECRET-" + suffix, "Site secret " + suffix));
    server = StatusPageTestSupport.equipment(equipments, site, "srv-messagerie-" + suffix);
    var newProbe = new MonitoredService(server, "IMAPS interne " + suffix, ServiceType.TCP_PORT);
    newProbe.setPort(8993);
    newProbe.setAccessPoint("mail-interne-" + suffix + ".ept.local");
    newProbe.setLastUp(true);
    newProbe.setLastCheckedAt(Instant.now());
    probe = services.saveAndFlush(newProbe);
    admin = StatusPageTestSupport.user(users, encoder, "sp-pub-adm-" + suffix, Role.ADMIN);
    token = login(mockMvc, admin);
  }

  private String slug() {
    return "pub-" + suffix + "-" + UUID.randomUUID().toString().substring(0, 4);
  }

  private MvcResult read(String slug) throws Exception {
    return mockMvc.perform(get(PUBLIC + slug).with(from(client))).andReturn();
  }

  @Test
  void sansConnexionLaPagePubliqueMontreEtatComposantsEtIncidents() throws Exception {
    String slug = slug();
    UUID page = createPage(mockMvc, token, slug, "PUBLIC");
    UUID component = addComponent(mockMvc, token, page, "Messagerie", "PROBE", probe.getId());
    createIncident(mockMvc, token, page, "Retards de courriels", component);

    mockMvc
        .perform(get(PUBLIC + slug).with(from(client)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.title").value("Services de l'école"))
        .andExpect(jsonPath("$.overallState").value("OPERATIONAL"))
        .andExpect(jsonPath("$.components[0].name").value("Messagerie"))
        .andExpect(jsonPath("$.components[0].group").value("Services"))
        .andExpect(jsonPath("$.components[0].state").value("OPERATIONAL"))
        .andExpect(jsonPath("$.components[0].daily.length()").value(90))
        .andExpect(jsonPath("$.availabilityDays").value(90))
        .andExpect(jsonPath("$.incidents[0].title").value("Retards de courriels"))
        .andExpect(jsonPath("$.incidents[0].components[0]").value("Messagerie"))
        .andExpect(jsonPath("$.generatedAt").isNotEmpty());
  }

  @Test
  void aucuneDonneeInterneNeFuit() throws Exception {
    String slug = slug();
    UUID page = createPage(mockMvc, token, slug, "PUBLIC");
    UUID probeComponent = addComponent(mockMvc, token, page, "Messagerie", "PROBE", probe.getId());
    addComponent(mockMvc, token, page, "Serveur", "EQUIPMENT", server.getId());
    addComponent(mockMvc, token, page, "Campus", "SITE", site.getId());
    UUID incident = createIncident(mockMvc, token, page, "Retards", probeComponent);
    mockMvc
        .perform(
            auth(post(BASE + "/" + page + "/incidents/" + incident + "/mises-a-jour"), token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"MONITORING\",\"message\":\"Correctif appliqué.\"}"))
        .andExpect(status().isCreated());
    // Alerte ouverte sur l'équipement : son identifiant ne doit pas apparaître.
    UUID alert = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO alert(id, equipment_id, occurrence_key, type, state, severity, message,"
            + " triggered_at) VALUES (?, ?, ?, 'COLLECTION', 'OPEN', 'CRITICAL', ?, now())",
        alert,
        server.getId(),
        "sp-leak-" + suffix,
        "Injoignable " + server.getManagementAddress());

    String body = read(slug).getResponse().getContentAsString();
    assertThat(body)
        .contains("Messagerie")
        .doesNotContain(server.getName())
        .doesNotContain(server.getManagementAddress())
        .doesNotContain(server.getInventoryCode())
        .doesNotContain(probe.getName())
        .doesNotContain(probe.getAccessPoint())
        .doesNotContain("8993")
        .doesNotContain(site.getName())
        .doesNotContain(site.getCode())
        .doesNotContain(admin.getUsername())
        .doesNotContain("Injoignable")
        .doesNotContainIgnoringCase("hostname")
        .doesNotContain("\"id\"")
        .doesNotContain("author")
        .doesNotContain("slug");
    // Aucun identifiant interne (page, composant, incident, équipement, sonde, alerte).
    assertThat(UUID_PATTERN.matcher(body).find()).as("identifiant interne dans %s", body).isFalse();
  }

  @Test
  void memeReponse404PourInconnuePriveeOuInactive() throws Exception {
    String internal = slug();
    createPage(mockMvc, token, internal, "INTERNAL");
    String inactive = slug();
    UUID inactivePage = createPage(mockMvc, token, inactive, "PUBLIC");
    mockMvc
        .perform(
            auth(put(BASE + "/" + inactivePage), token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"slug\":\""
                        + inactive
                        + "\",\"title\":\"X\",\"visibility\":\"PUBLIC\",\"active\":false}"))
        .andExpect(status().isOk());

    MvcResult unknown = read("inconnue-" + suffix);
    MvcResult privatePage = read(internal);
    MvcResult inactivePublic = read(inactive);
    MvcResult malformed = read("NOT_A_SLUG");
    for (MvcResult result : new MvcResult[] {unknown, privatePage, inactivePublic, malformed}) {
      assertThat(result.getResponse().getStatus()).isEqualTo(404);
      assertThat(result.getResponse().getContentAsString())
          .isEqualTo(unknown.getResponse().getContentAsString());
      assertThat(result.getResponse().getContentAsString()).doesNotContain("Exception", "trace");
    }
  }

  @Test
  void enTetesDeCacheEtDeSecurite_sansCookie() throws Exception {
    String slug = slug();
    createPage(mockMvc, token, slug, "PUBLIC");
    mockMvc
        .perform(get(PUBLIC + slug).with(from(client)))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "max-age=30, public"))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(header().doesNotExist("Set-Cookie"))
        .andExpect(header().doesNotExist("Pragma"));
  }

  @Test
  void laPagePubliqueEstEnLectureSeule() throws Exception {
    String slug = slug();
    createPage(mockMvc, token, slug, "PUBLIC");
    // Sans jeton : aucune méthode d'écriture n'est ouverte.
    mockMvc
        .perform(
            post(PUBLIC + slug)
                .with(from(client))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            put(PUBLIC + slug)
                .with(from(client))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(delete(PUBLIC + slug).with(from(client))).andExpect(status().isUnauthorized());
    // Même authentifié, la ressource publique n'accepte que la lecture.
    mockMvc
        .perform(
            auth(post(PUBLIC + slug), token)
                .with(from(client))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isMethodNotAllowed());
    mockMvc
        .perform(auth(delete(PUBLIC + slug), token).with(from(client)))
        .andExpect(status().isMethodNotAllowed());
    // Aucun autre chemin public n'est ouvert sans connexion.
    mockMvc
        .perform(get("/api/v1/public/autre").with(from(client)))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(get(PUBLIC + slug + "/composants").with(from(client)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void limiteParAdresse_429AvecRetryAfter_XForwardedForIgnore() throws Exception {
    String address = randomAddress();
    for (int i = 0; i < 60; i++) {
      mockMvc
          .perform(get(PUBLIC + "limite-" + suffix).with(from(address)))
          .andExpect(status().isNotFound());
    }
    mockMvc
        .perform(get(PUBLIC + "limite-" + suffix).with(from(address)))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists("Retry-After"))
        .andExpect(jsonPath("$.error").value("Trop de requêtes. Réessayez plus tard."));
    // X-Forwarded-For n'est pas lu (server.forward-headers-strategy=none par défaut) : changer
    // l'en-tête ne contourne pas la limite.
    mockMvc
        .perform(
            get(PUBLIC + "limite-" + suffix)
                .with(from(address))
                .header("X-Forwarded-For", randomAddress()))
        .andExpect(status().isTooManyRequests());
    // Une autre adresse a son propre compteur.
    mockMvc
        .perform(get(PUBLIC + "limite-" + suffix).with(from(randomAddress())))
        .andExpect(status().isNotFound());
    // L'API authentifiée n'est pas concernée par cette limite.
    mockMvc.perform(auth(get(BASE), token).with(from(address))).andExpect(status().isOk());
  }

  @Test
  void lInstantaneEstGardeEnCache_uneEcritureDeLAdministrateurLeRenouvelle() throws Exception {
    String slug = slug();
    UUID page = createPage(mockMvc, token, slug, "PUBLIC");
    assertThat(read(slug).getResponse().getContentAsString()).contains("\"incidents\":[]");
    // Écriture directe en base, sans passer par le service : le cache sert encore l'ancien rendu.
    jdbc.update(
        "INSERT INTO status_incident(id, page_id, title, status, impact, message, created_at)"
            + " VALUES (?, ?, 'Hors cache', 'INVESTIGATING', 'MINOR', 'm', now())",
        UUID.randomUUID(),
        page);
    assertThat(read(slug).getResponse().getContentAsString()).doesNotContain("Hors cache");
    // Une écriture par l'API invalide le cache.
    createIncident(mockMvc, token, page, "Par l'API", null);
    assertThat(read(slug).getResponse().getContentAsString())
        .contains("Hors cache")
        .contains("Par l'API");
  }

  @Test
  void disponibiliteCalculeeDepuisLesMesuresDeSonde_maintenanceExclue() throws Exception {
    String slug = slug();
    UUID page = createPage(mockMvc, token, slug, "PUBLIC");
    addComponent(mockMvc, token, page, "Messagerie", "PROBE", probe.getId());
    Instant yesterday = Instant.now().truncatedTo(ChronoUnit.DAYS).minus(1, ChronoUnit.DAYS);
    // Hier : 3 mesures en ligne et 1 en panne à 10 h, 2 en panne à 14 h (pendant une maintenance).
    metric(yesterday.plus(10, ChronoUnit.HOURS), 100);
    metric(yesterday.plus(10, ChronoUnit.HOURS).plusSeconds(60), 100);
    metric(yesterday.plus(10, ChronoUnit.HOURS).plusSeconds(120), 100);
    metric(yesterday.plus(10, ChronoUnit.HOURS).plusSeconds(180), 0);
    metric(yesterday.plus(14, ChronoUnit.HOURS), 0);
    metric(yesterday.plus(14, ChronoUnit.HOURS).plusSeconds(60), 0);
    jdbc.update(
        "INSERT INTO maintenance(id, planner_id, equipment_id, start_at, end_at, reason, state,"
            + " created_at) VALUES (?, ?, ?, ?, ?, 'Migration', 'COMPLETED', now())",
        UUID.randomUUID(),
        admin.getId(),
        server.getId(),
        Timestamp.from(yesterday.plus(14, ChronoUnit.HOURS)),
        Timestamp.from(yesterday.plus(15, ChronoUnit.HOURS)));
    // Le cache de disponibilité est vidé par l'ajout du composant ; on relit après les mesures.
    createIncident(mockMvc, token, page, "Rafraîchir", null);

    mockMvc
        .perform(get(PUBLIC + slug).with(from(client)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.components[0].daily[88]").value(75.0))
        // Jour sans mesure : inconnu, jamais 100 %.
        .andExpect(jsonPath("$.components[0].daily[87]").doesNotExist())
        .andExpect(jsonPath("$.components[0].availability").value(75.0));
  }

  private void metric(Instant at, int value) {
    jdbc.update(
        "INSERT INTO metric(equipment_id, service_id, metric_key, metric_value, unit, quality,"
            + " collected_at, received_at) VALUES (?, ?, 'probe.up', ?, '%', 'GOOD', ?, now())",
        server.getId(), probe.getId(), BigDecimal.valueOf(value), Timestamp.from(at));
  }
}

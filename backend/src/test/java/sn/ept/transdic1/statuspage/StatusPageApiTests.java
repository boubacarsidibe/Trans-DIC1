package sn.ept.transdic1.statuspage;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static sn.ept.transdic1.statuspage.StatusPageTestSupport.BASE;
import static sn.ept.transdic1.statuspage.StatusPageTestSupport.addComponent;
import static sn.ept.transdic1.statuspage.StatusPageTestSupport.auth;
import static sn.ept.transdic1.statuspage.StatusPageTestSupport.createIncident;
import static sn.ept.transdic1.statuspage.StatusPageTestSupport.createPage;
import static sn.ept.transdic1.statuspage.StatusPageTestSupport.login;

import java.time.Instant;
import java.util.UUID;
import org.assertj.core.api.Assertions;
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
 * API d'administration et de lecture interne des pages de statut (ADR 0026) : écriture ADMIN
 * validée, bornée et tracée ; lecture pour tous les rôles filtrée par le périmètre de sites.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class StatusPageApiTests {
  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private PasswordEncoder encoder;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private MonitoredServiceRepository services;
  @Autowired private SiteAccessRepository access;
  @Autowired private JdbcTemplate jdbc;

  private final String suffix = StatusPageTestSupport.suffix();
  private Site siteA;
  private Site siteB;
  private Equipment serverA;
  private MonitoredService probeA;
  private AppUser admin;
  private AppUser noc;
  private AppUser viewer;
  private AppUser limited;

  @BeforeEach
  void data() {
    siteA = sites.saveAndFlush(new Site("SPA-" + suffix, "Campus A " + suffix));
    siteB = sites.saveAndFlush(new Site("SPB-" + suffix, "Campus B " + suffix));
    serverA = StatusPageTestSupport.equipment(equipments, siteA, "srv-a-" + suffix);
    probeA = StatusPageTestSupport.probe(services, serverA, "HTTPS ENT", ServiceType.HTTPS);
    admin = StatusPageTestSupport.user(users, encoder, "sp-adm-" + suffix, Role.ADMIN);
    noc = StatusPageTestSupport.user(users, encoder, "sp-noc-" + suffix, Role.NOC_OPERATOR);
    viewer = StatusPageTestSupport.user(users, encoder, "sp-view-" + suffix, Role.VIEWER);
    limited = StatusPageTestSupport.user(users, encoder, "sp-lim-" + suffix, Role.VIEWER);
    access.saveAndFlush(new SiteAccess(limited, siteA, Instant.now()));
  }

  private String slug() {
    return "sp-" + suffix + "-" + UUID.randomUUID().toString().substring(0, 4);
  }

  @Test
  void sansJetonToutEst401() throws Exception {
    mockMvc.perform(get(BASE)).andExpect(status().isUnauthorized());
  }

  @Test
  void unAdministrateurCreeUnePageUnComposantEtUnIncident_etToutEstTrace() throws Exception {
    String token = login(mockMvc, admin);
    UUID page = createPage(mockMvc, token, slug(), "INTERNAL");
    UUID component =
        addComponent(mockMvc, token, page, "Espace numérique", "PROBE", probeA.getId());
    UUID incident = createIncident(mockMvc, token, page, "Lenteurs ENT", component);

    mockMvc
        .perform(
            auth(post(BASE + "/" + page + "/incidents/" + incident + "/mises-a-jour"), token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"RESOLVED\",\"message\":\"Service rétabli.\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("RESOLVED"))
        .andExpect(jsonPath("$.resolvedAt").isNotEmpty())
        .andExpect(jsonPath("$.updates.length()").value(1));

    mockMvc
        .perform(auth(get(BASE + "/" + page), token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.components[0].displayName").value("Espace numérique"))
        .andExpect(
            jsonPath("$.components[0].sourceLabel").value("srv-a-" + suffix + " — HTTPS ENT"))
        .andExpect(jsonPath("$.components[0].state").value("UNKNOWN"))
        .andExpect(jsonPath("$.incidents[0].author").value(admin.getUsername()));

    Assertions.assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_entry WHERE action IN ('STATUS_PAGE_CREATED',"
                    + " 'STATUS_COMPONENT_ADDED', 'STATUS_INCIDENT_CREATED',"
                    + " 'STATUS_INCIDENT_UPDATE_ADDED') AND username = ?",
                Integer.class,
                admin.getUsername()))
        .isEqualTo(4);
  }

  @Test
  void seulUnAdministrateurEcrit() throws Exception {
    String adminToken = login(mockMvc, admin);
    UUID page = createPage(mockMvc, adminToken, slug(), "INTERNAL");
    for (AppUser user : new AppUser[] {noc, viewer}) {
      String token = login(mockMvc, user);
      mockMvc
          .perform(
              auth(post(BASE), token)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      "{\"slug\":\"" + slug() + "\",\"title\":\"X\",\"visibility\":\"PUBLIC\"}"))
          .andExpect(status().isForbidden());
      mockMvc.perform(auth(delete(BASE + "/" + page), token)).andExpect(status().isForbidden());
      mockMvc.perform(auth(get(BASE + "/" + page), token)).andExpect(status().isForbidden());
      mockMvc
          .perform(auth(get(BASE + "/sources?type=PROBE"), token))
          .andExpect(status().isForbidden());
      // Lecture : la vue interne reste ouverte à tous les rôles.
      mockMvc.perform(auth(get(BASE + "/" + page + "/vue"), token)).andExpect(status().isOk());
    }
  }

  @Test
  void laSaisieEstValideeEnFrancais_texteBrutSeulement() throws Exception {
    String token = login(mockMvc, admin);
    mockMvc
        .perform(
            auth(post(BASE), token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"slug\":\"Mauvais Slug\",\"title\":\"\",\"visibility\":\"PUBLIC\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fields.slug").value(Matchers.containsString("minuscules")))
        .andExpect(jsonPath("$.fields.title").value("Indiquez le titre de la page."));
    mockMvc
        .perform(
            auth(post(BASE), token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"slug\":\""
                        + slug()
                        + "\",\"title\":\"<script>alert(1)</script>\",\"visibility\":\"PUBLIC\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fields.title").value(Matchers.containsString("Texte brut")));

    UUID page = createPage(mockMvc, token, slug(), "INTERNAL");
    mockMvc
        .perform(
            auth(post(BASE + "/" + page + "/incidents"), token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"title\":\"T\",\"status\":\"INVESTIGATING\",\"impact\":\"MINOR\",\"message\":\""
                        + "x".repeat(2001)
                        + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fields.message").value("Message : 2000 caractères au plus."));
    // Source inexistante : pas de nom d'hôte libre, seulement une source supervisée existante.
    mockMvc
        .perform(
            auth(post(BASE + "/" + page + "/composants"), token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"displayName\":\"Wi-Fi\",\"sourceType\":\"EQUIPMENT\",\"sourceId\":\""
                        + UUID.randomUUID()
                        + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value(Matchers.containsString("n'existe pas")));
  }

  @Test
  void slugUnique() throws Exception {
    String token = login(mockMvc, admin);
    String slug = slug();
    createPage(mockMvc, token, slug, "INTERNAL");
    mockMvc
        .perform(
            auth(post(BASE), token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"slug\":\"" + slug + "\",\"title\":\"X\",\"visibility\":\"INTERNAL\"}"))
        .andExpect(status().isConflict());
  }

  @Test
  void auPlusDixPagesEtCinquanteComposantsParPage() throws Exception {
    String token = login(mockMvc, admin);
    Integer existing = jdbc.queryForObject("SELECT count(*) FROM status_page", Integer.class);
    UUID first = null;
    for (int i = existing; i < 10; i++) {
      UUID id = createPage(mockMvc, token, slug(), "INTERNAL");
      if (first == null) first = id;
    }
    mockMvc
        .perform(
            auth(post(BASE), token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"slug\":\"" + slug() + "\",\"title\":\"X\",\"visibility\":\"INTERNAL\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value(Matchers.containsString("10")));

    for (int i = 0; i < 50; i++) {
      addComponent(mockMvc, token, first, "Composant " + i, "SITE", siteA.getId());
    }
    mockMvc
        .perform(
            auth(post(BASE + "/" + first + "/composants"), token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"displayName\":\"De trop\",\"sourceType\":\"SITE\",\"sourceId\":\""
                        + siteA.getId()
                        + "\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value(Matchers.containsString("50")));
  }

  @Test
  void unCompteLimiteNeVoitQueLesComposantsDeSonPerimetre() throws Exception {
    String token = login(mockMvc, admin);
    UUID page = createPage(mockMvc, token, slug(), "INTERNAL");
    UUID visible = addComponent(mockMvc, token, page, "Campus A", "SITE", siteA.getId());
    UUID hidden = addComponent(mockMvc, token, page, "Campus B", "SITE", siteB.getId());
    createIncident(mockMvc, token, page, "Panne campus B", hidden);
    createIncident(mockMvc, token, page, "Panne campus A", visible);

    mockMvc
        .perform(auth(get(BASE + "/" + page + "/vue"), login(mockMvc, limited)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.components[*].name", Matchers.contains("Campus A")))
        .andExpect(jsonPath("$.incidents[*].title", Matchers.contains("Panne campus A")));
    mockMvc
        .perform(auth(get(BASE + "/" + page + "/vue"), login(mockMvc, viewer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.components.length()").value(2))
        .andExpect(jsonPath("$.incidents.length()").value(2));
  }

  @Test
  void unePageInactiveNEstVisibleQueParUnAdministrateur() throws Exception {
    String token = login(mockMvc, admin);
    String slug = slug();
    UUID page = createPage(mockMvc, token, slug, "INTERNAL");
    mockMvc
        .perform(
            auth(put(BASE + "/" + page), token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"slug\":\""
                        + slug
                        + "\",\"title\":\"Inactive\",\"visibility\":\"INTERNAL\",\"active\":false}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.active").value(false));
    String viewerToken = login(mockMvc, viewer);
    mockMvc
        .perform(auth(get(BASE + "/" + page + "/vue"), viewerToken))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(auth(get(BASE), viewerToken))
        .andExpect(jsonPath("$[*].id", Matchers.not(Matchers.hasItem(page.toString()))));
    mockMvc.perform(auth(get(BASE + "/" + page + "/vue"), token)).andExpect(status().isOk());
  }

  @Test
  void etatsLusEnBase_maintenanceEnCoursEtEquipementEnPanne() throws Exception {
    String token = login(mockMvc, admin);
    UUID page = createPage(mockMvc, token, slug(), "INTERNAL");
    addComponent(mockMvc, token, page, "Serveur A", "EQUIPMENT", serverA.getId());
    addComponent(mockMvc, token, page, "Campus B", "SITE", siteB.getId());
    Equipment serverB = StatusPageTestSupport.equipment(equipments, siteB, "srv-b-" + suffix);
    jdbc.update("UPDATE equipment SET operational_status = 'DOWN' WHERE id = ?", serverB.getId());
    jdbc.update(
        "INSERT INTO maintenance(id, planner_id, site_id, start_at, end_at, reason, state,"
            + " created_at) VALUES (?, ?, ?, now() - interval '5 minutes',"
            + " now() + interval '1 hour', 'Travaux', 'IN_PROGRESS', now())",
        UUID.randomUUID(),
        admin.getId(),
        siteA.getId());

    mockMvc
        .perform(auth(get(BASE + "/" + page), token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.components[0].state").value("MAINTENANCE"))
        .andExpect(jsonPath("$.components[1].state").value("MAJOR_OUTAGE"))
        .andExpect(jsonPath("$.overallState").value("MAJOR_OUTAGE"));
  }

  @Test
  void rechercheDesSources() throws Exception {
    String token = login(mockMvc, admin);
    mockMvc
        .perform(
            auth(
                get(BASE + "/sources").param("type", "PROBE").param("q", "srv-a-" + suffix), token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(probeA.getId().toString()))
        .andExpect(jsonPath("$[0].label").value("HTTPS ENT"));
    mockMvc
        .perform(
            auth(
                get(BASE + "/sources").param("type", "SITE").param("q", "Campus B " + suffix),
                token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].id").value(siteB.getId().toString()));
  }

  @Test
  void modifierEtSupprimerComposantEtIncident_unIncidentAutomatiqueSeModifie() throws Exception {
    String token = login(mockMvc, admin);
    UUID page = createPage(mockMvc, token, slug(), "INTERNAL");
    UUID component = addComponent(mockMvc, token, page, "Wi-Fi", "SITE", siteA.getId());
    UUID incident = createIncident(mockMvc, token, page, "Coupure Wi-Fi", component);
    mockMvc
        .perform(
            auth(put(BASE + "/" + page + "/composants/" + component), token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"displayName\":\"Wi-Fi campus\",\"sourceType\":\"SITE\",\"sourceId\":\""
                        + siteA.getId()
                        + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.displayName").value("Wi-Fi campus"));
    mockMvc
        .perform(
            auth(put(BASE + "/" + page + "/incidents/" + incident), token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"title\":\"Coupure Wi-Fi (bâtiment B)\",\"status\":\"IDENTIFIED\","
                        + "\"impact\":\"MINOR\",\"message\":\"Borne remplacée.\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("IDENTIFIED"));
    mockMvc
        .perform(auth(delete(BASE + "/" + page + "/incidents/" + incident), token))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(auth(delete(BASE + "/" + page + "/composants/" + component), token))
        .andExpect(status().isNoContent());
    mockMvc.perform(auth(delete(BASE + "/" + page), token)).andExpect(status().isNoContent());
    mockMvc.perform(auth(get(BASE + "/" + page), token)).andExpect(status().isNotFound());
  }
}

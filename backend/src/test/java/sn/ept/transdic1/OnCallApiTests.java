package sn.ept.transdic1;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.access.domain.SiteAccess;
import sn.ept.transdic1.access.repository.SiteAccessRepository;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.repository.SiteRepository;
import sn.ept.transdic1.security.domain.AppUser;
import sn.ept.transdic1.security.domain.Role;
import sn.ept.transdic1.security.repository.UserRepository;

/**
 * API d'astreinte : lecture pour tous les rôles dans le périmètre, écriture ADMIN validée et
 * tracée, passation d'une garde par son titulaire ou un administrateur.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class OnCallApiTests {
  private static final String PASSWORD = "Mot-de-passe-test-123";
  private static final String BASE = "/api/v1/astreinte";

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private PasswordEncoder encoder;
  @Autowired private SiteRepository sites;
  @Autowired private SiteAccessRepository access;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private jakarta.persistence.EntityManager em;

  private final String suffix = UUID.randomUUID().toString().substring(0, 8);
  private Site siteA;
  private Site siteB;
  private AppUser admin;
  private AppUser noc;
  private AppUser otherNoc;
  private AppUser viewer;
  private AppUser limited;

  @BeforeEach
  void data() {
    siteA = sites.saveAndFlush(new Site("OCA-" + suffix, "Site A " + suffix));
    siteB = sites.saveAndFlush(new Site("OCB-" + suffix, "Site B " + suffix));
    admin = user("oc-adm-", Role.ADMIN);
    noc = user("oc-noc-", Role.NOC_OPERATOR);
    otherNoc = user("oc-noc2-", Role.NOC_OPERATOR);
    viewer = user("oc-view-", Role.VIEWER);
    limited = user("oc-lim-", Role.NOC_OPERATOR);
    access.saveAndFlush(new SiteAccess(limited, siteA, Instant.now()));
  }

  @Test
  void sansJetonTout401() throws Exception {
    mockMvc.perform(get(BASE + "/maintenant")).andExpect(status().isUnauthorized());
  }

  @Test
  void unAdministrateurCreeUnPlanningEtUneGarde_unLecteurVoitQuiEstDAstreinte() throws Exception {
    String adminToken = login(admin);
    UUID planning = createPlanning(adminToken, "Astreinte A " + suffix, siteA.getId());
    Instant start = Instant.now().minus(1, ChronoUnit.HOURS);
    createShift(adminToken, planning, noc.getId(), "PRIMARY", start, start.plus(1, ChronoUnit.DAYS))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.username").value(noc.getUsername()))
        .andExpect(jsonPath("$.generated").value(false));

    mockMvc
        .perform(auth(get(BASE + "/maintenant"), login(viewer)))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath(
                "$[?(@.siteId == '" + siteA.getId() + "')].primary.username",
                Matchers.contains(noc.getUsername())))
        .andExpect(
            jsonPath(
                "$[?(@.siteId == '" + siteB.getId() + "')].covered", Matchers.contains(false)));

    Assertions.assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_entry WHERE action IN"
                    + " ('ONCALL_SCHEDULE_CREATED', 'ONCALL_SHIFT_CREATED') AND username = ?",
                Integer.class,
                admin.getUsername()))
        .isEqualTo(2);
  }

  @Test
  void unCompteLimiteNeVoitQueSesSitesEtSesPlannings() throws Exception {
    String adminToken = login(admin);
    UUID planningB = createPlanning(adminToken, "Astreinte B " + suffix, siteB.getId());
    String token = login(limited);

    mockMvc
        .perform(auth(get(BASE + "/maintenant"), token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[*].siteId", Matchers.hasItem(siteA.getId().toString())))
        .andExpect(
            jsonPath("$[*].siteId", Matchers.not(Matchers.hasItem(siteB.getId().toString()))));
    mockMvc
        .perform(auth(get(BASE + "/plannings"), token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[*].id", Matchers.not(Matchers.hasItem(planningB.toString()))));
    mockMvc
        .perform(auth(get(BASE + "/plannings/" + planningB + "/gardes"), token))
        .andExpect(status().isNotFound());
  }

  @Test
  void seulUnAdministrateurEcritLesPlannings() throws Exception {
    for (AppUser user : new AppUser[] {noc, viewer}) {
      mockMvc
          .perform(
              auth(post(BASE + "/plannings"), login(user))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"name\":\"Interdit " + suffix + "\",\"allSites\":true}"))
          .andExpect(status().isForbidden());
    }
  }

  @Test
  void lesErreursDeSaisieSontExpliqueesEnFrancais() throws Exception {
    String token = login(admin);
    mockMvc
        .perform(
            auth(post(BASE + "/plannings"), token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\",\"allSites\":true}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fields.name").value("Le nom du planning est obligatoire."));
    mockMvc
        .perform(
            auth(post(BASE + "/plannings"), token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"Fuseau "
                        + suffix
                        + "\",\"timezone\":\"Mars/Olympus\",\"allSites\":true}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error", Matchers.containsString("Fuseau horaire inconnu")));
    mockMvc
        .perform(
            auth(post(BASE + "/plannings"), token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Sans site " + suffix + "\",\"allSites\":false}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error", Matchers.containsString("au moins un site")));

    UUID planning = createPlanning(token, "Validation " + suffix, null);
    mockMvc
        .perform(
            auth(post(BASE + "/plannings"), token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"validation " + suffix + "\",\"allSites\":true}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value("Un planning porte déjà ce nom."));
    Instant start = Instant.now().plus(1, ChronoUnit.DAYS);
    createShift(token, planning, noc.getId(), "PRIMARY", start, start.minusSeconds(60))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("La fin de la garde doit être postérieure au début."));
    createShift(token, planning, viewer.getId(), "PRIMARY", start, start.plusSeconds(3600))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.error", Matchers.containsString("administrateur ou un opérateur NOC")));
  }

  @Test
  void rotation_apercuPuisGeneration_lesGardesGenereesNeSeSupprimentPasUneAUne() throws Exception {
    String token = login(admin);
    UUID planning = createPlanning(token, "Rotation " + suffix, siteA.getId());
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    mockMvc
        .perform(
            auth(put(BASE + "/plannings/" + planning + "/rotation"), token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"level\":\"PRIMARY\",\"userIds\":[\""
                        + noc.getId()
                        + "\",\""
                        + otherNoc.getId()
                        + "\"],\"startDate\":\""
                        + today
                        + "\",\"shiftLengthDays\":7,\"handoverTime\":\"08:00\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.members.length()").value(2));

    mockMvc
        .perform(
            auth(post(BASE + "/plannings/" + planning + "/rotation/generer"), token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"level\":\"PRIMARY\",\"horizonDays\":28,\"preview\":true}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.preview").value(true))
        .andExpect(jsonPath("$.count", Matchers.greaterThanOrEqualTo(4)));
    mockMvc
        .perform(auth(get(BASE + "/plannings/" + planning + "/gardes"), token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));

    String generated =
        mockMvc
            .perform(
                auth(post(BASE + "/plannings/" + planning + "/rotation/generer"), token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"level\":\"PRIMARY\",\"horizonDays\":28,\"preview\":false}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.preview").value(false))
            .andReturn()
            .getResponse()
            .getContentAsString();
    int count = JsonPath.read(generated, "$.count");
    String shifts =
        mockMvc
            .perform(auth(get(BASE + "/plannings/" + planning + "/gardes"), token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(count))
            .andExpect(jsonPath("$[0].generated").value(true))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String shiftId = JsonPath.read(shifts, "$[0].id");
    mockMvc
        .perform(auth(delete(BASE + "/plannings/" + planning + "/gardes/" + shiftId), token))
        .andExpect(status().isConflict());
  }

  @Test
  void passation_parLeTitulaireOuUnAdministrateur_pasParUnAutreOperateur() throws Exception {
    String adminToken = login(admin);
    UUID planning = createPlanning(adminToken, "Passation " + suffix, siteA.getId());
    Instant start = Instant.now().minus(1, ChronoUnit.HOURS);
    String body =
        createShift(
                adminToken, planning, noc.getId(), "PRIMARY", start, start.plus(1, ChronoUnit.DAYS))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String shiftId = JsonPath.read(body, "$.id");
    String handover = "{\"userId\":\"" + otherNoc.getId() + "\",\"note\":\"Rendez-vous médical\"}";

    // Un autre opérateur ne peut pas passer la garde de quelqu'un d'autre ; un lecteur non plus.
    mockMvc
        .perform(
            auth(post(BASE + "/gardes/" + shiftId + "/remplacer"), login(otherNoc))
                .contentType(MediaType.APPLICATION_JSON)
                .content(handover))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            auth(post(BASE + "/gardes/" + shiftId + "/remplacer"), login(viewer))
                .contentType(MediaType.APPLICATION_JSON)
                .content(handover))
        .andExpect(status().isForbidden());

    // Le titulaire passe sa garde : un remplacement est créé et prend effet tout de suite.
    mockMvc
        .perform(
            auth(post(BASE + "/gardes/" + shiftId + "/remplacer"), login(noc))
                .contentType(MediaType.APPLICATION_JSON)
                .content(handover))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.override").value(true))
        .andExpect(jsonPath("$.username").value(otherNoc.getUsername()))
        .andExpect(jsonPath("$.replacesShiftId").value(shiftId));
    mockMvc
        .perform(auth(get(BASE + "/maintenant"), login(viewer)))
        .andExpect(
            jsonPath(
                "$[?(@.siteId == '" + siteA.getId() + "')].primary.username",
                Matchers.contains(otherNoc.getUsername())));
    mockMvc
        .perform(auth(get(BASE + "/mes-gardes"), login(otherNoc)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].override").value(true));

    // L'administrateur peut aussi passer une garde ; on ne passe pas une garde à son titulaire.
    mockMvc
        .perform(
            auth(post(BASE + "/gardes/" + shiftId + "/remplacer"), adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + admin.getId() + "\"}"))
        .andExpect(status().isCreated());
    mockMvc
        .perform(
            auth(post(BASE + "/gardes/" + shiftId + "/remplacer"), adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + noc.getId() + "\"}"))
        .andExpect(status().isBadRequest());
    em.flush();
    Assertions.assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_entry WHERE action = 'ONCALL_SHIFT_HANDOVER'"
                    + " AND target_id = ?::uuid",
                Integer.class,
                shiftId))
        .isEqualTo(2);
  }

  @Test
  void uneGardeTermineeNePeutPlusEtrePassee() throws Exception {
    String adminToken = login(admin);
    UUID planning = createPlanning(adminToken, "Terminee " + suffix, null);
    Instant start = Instant.now().minus(2, ChronoUnit.DAYS);
    String body =
        createShift(
                adminToken, planning, noc.getId(), "PRIMARY", start, start.plus(1, ChronoUnit.DAYS))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String shiftId = JsonPath.read(body, "$.id");

    mockMvc
        .perform(
            auth(post(BASE + "/gardes/" + shiftId + "/remplacer"), login(noc))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + otherNoc.getId() + "\"}"))
        .andExpect(status().isConflict());
  }

  @Test
  void mesGardesNeMontreQueLesGardesDeLAppelant() throws Exception {
    String adminToken = login(admin);
    UUID planning = createPlanning(adminToken, "Mes gardes " + suffix, siteA.getId());
    Instant start = Instant.now().plus(2, ChronoUnit.DAYS);
    createShift(
        adminToken, planning, noc.getId(), "PRIMARY", start, start.plus(1, ChronoUnit.DAYS));
    createShift(
        adminToken, planning, otherNoc.getId(), "SECONDARY", start, start.plus(1, ChronoUnit.DAYS));
    // Au-delà de 60 jours : hors de « mes gardes ».
    Instant far = Instant.now().plus(70, ChronoUnit.DAYS);
    createShift(adminToken, planning, noc.getId(), "PRIMARY", far, far.plus(1, ChronoUnit.DAYS));

    mockMvc
        .perform(auth(get(BASE + "/mes-gardes"), login(noc)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].level").value("PRIMARY"))
        .andExpect(jsonPath("$[0].scheduleName").value("Mes gardes " + suffix));
  }

  private UUID createPlanning(String token, String name, UUID siteId) throws Exception {
    String content =
        siteId == null
            ? "{\"name\":\"" + name + "\",\"allSites\":true}"
            : "{\"name\":\"" + name + "\",\"allSites\":false,\"siteIds\":[\"" + siteId + "\"]}";
    String body =
        mockMvc
            .perform(
                auth(post(BASE + "/plannings"), token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(content))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.timezone").value("Africa/Dakar"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    return UUID.fromString(JsonPath.read(body, "$.id"));
  }

  private org.springframework.test.web.servlet.ResultActions createShift(
      String token, UUID planning, UUID userId, String level, Instant start, Instant end)
      throws Exception {
    return mockMvc.perform(
        auth(post(BASE + "/plannings/" + planning + "/gardes"), token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"userId\":\""
                    + userId
                    + "\",\"level\":\""
                    + level
                    + "\",\"startsAt\":\""
                    + start
                    + "\",\"endsAt\":\""
                    + end
                    + "\"}"));
  }

  private static MockHttpServletRequestBuilder auth(
      MockHttpServletRequestBuilder request, String token) {
    return request.header("Authorization", "Bearer " + token);
  }

  private AppUser user(String prefix, Role role) {
    return users.saveAndFlush(
        new AppUser(
            prefix + suffix, prefix + suffix + "@example.test", encoder.encode(PASSWORD), role));
  }

  private String login(AppUser user) throws Exception {
    var body =
        mockMvc
            .perform(
                post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"username\":\""
                            + user.getUsername()
                            + "\",\"password\":\""
                            + PASSWORD
                            + "\"}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(body, "$.accessToken");
  }
}

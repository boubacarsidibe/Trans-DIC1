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
import sn.ept.transdic1.alerting.service.AlertLevelService;
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
 * Niveaux de support, journal d'intervention, escalade et renvoi (ADR 0059) : droits, saut d'un
 * niveau vide, destinataires, traçabilité, idempotence et indicateurs.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AlertLevelApiTests {
  private static final String PASSWORD = "Mot-de-passe-test-123";

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private PasswordEncoder encoder;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private AlertRepository alerts;
  @Autowired private AlertLevelService levels;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private jakarta.persistence.EntityManager em;

  private String suffix;
  private Site site;
  private Equipment equipment;
  private AppUser admin;
  private AppUser n1;
  private AppUser n2;
  private String adminJwt;
  private String n1Jwt;
  private String n2Jwt;
  private String viewerJwt;

  @BeforeEach
  void data() throws Exception {
    // Isolement : seuls les comptes de ce test comptent pour les niveaux occupés.
    jdbc.update("UPDATE app_user SET active = FALSE");
    suffix = UUID.randomUUID().toString().substring(0, 8);
    admin = account("lv-adm-", Role.ADMIN, null);
    n1 = account("lv-n1-", Role.NOC_OPERATOR, 1);
    n2 = account("lv-n2-", Role.NOC_OPERATOR, 2);
    account("lv-vwr-", Role.VIEWER, null);
    adminJwt = login("lv-adm-" + suffix);
    n1Jwt = login("lv-n1-" + suffix);
    n2Jwt = login("lv-n2-" + suffix);
    viewerJwt = login("lv-vwr-" + suffix);
    site = sites.save(new Site("LV-" + suffix, "Site des niveaux"));
    equipment = newEquipment();
    em.flush();
  }

  /** Un équipement par alerte : une seule alerte de collecte active par équipement. */
  private Equipment newEquipment() {
    String unique = UUID.randomUUID().toString();
    return equipments.saveAndFlush(
        new Equipment(
            "LV-EQ-" + unique,
            "Serveur ENT",
            "10.237."
                + (Math.floorMod(unique.hashCode(), 250) + 1)
                + "."
                + (Math.floorMod(unique.substring(9).hashCode(), 250) + 1),
            EquipmentType.SERVER,
            EquipmentStatus.UP,
            site));
  }

  private AppUser account(String prefix, Role role, Integer level) {
    var user =
        new AppUser(
            prefix + suffix, prefix + suffix + "@example.test", encoder.encode(PASSWORD), role);
    user.setSupportLevel(level);
    return users.saveAndFlush(user);
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

  private Alert alert(Integer level, Severity severity) {
    var alert =
        new Alert(
            newEquipment(),
            "lv:" + UUID.randomUUID(),
            AlertType.COLLECTION,
            AlertState.OPEN,
            severity,
            "Plus de réponse",
            Instant.now());
    alert.setCurrentLevel(level);
    return alerts.saveAndFlush(alert);
  }

  private ResultActions call(String path, String jwt, String body) throws Exception {
    var request = post("/api/v1/alertes/" + path).header("Authorization", "Bearer " + jwt);
    if (body != null) {
      request = request.contentType(MediaType.APPLICATION_JSON).content(body);
    }
    return mockMvc.perform(request);
  }

  private int count(String sql, Object... args) {
    em.flush();
    return jdbc.queryForObject(sql, Integer.class, args);
  }

  @Test
  void lAdministrateurFixeLeNiveauDUnOperateurSeulement() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/utilisateurs/" + n1.getId() + "/niveau")
                .header("Authorization", "Bearer " + adminJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"level\":3}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.supportLevel").value(3));
    mockMvc
        .perform(
            post("/api/v1/utilisateurs/" + admin.getId() + "/niveau")
                .header("Authorization", "Bearer " + adminJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"level\":1}"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            post("/api/v1/utilisateurs/" + n1.getId() + "/niveau")
                .header("Authorization", "Bearer " + n2Jwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"level\":1}"))
        .andExpect(status().isForbidden());
    assertThat(
            count("SELECT count(*) FROM audit_entry WHERE action = 'USER_SUPPORT_LEVEL_CHANGED'"))
        .isEqualTo(1);
    // Le badge en haut de l'écran lit le niveau ici.
    mockMvc
        .perform(get("/api/v1/auth/moi").header("Authorization", "Bearer " + n2Jwt))
        .andExpect(jsonPath("$.supportLevel").value(2));
  }

  @Test
  void unOperateurPrendSonNiveauOuInferieurEtLAlerteSansNiveauPrendLeSien() throws Exception {
    Alert high = alert(2, Severity.WARNING);
    call(high.getId() + "/prise-en-charge", n1Jwt, "{}")
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("N2")));
    call(high.getId() + "/prise-en-charge", n2Jwt, "{}").andExpect(status().isOk());

    Alert free = alert(null, Severity.WARNING);
    call(free.getId() + "/prise-en-charge", n1Jwt, "{}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.currentLevel").value(1));
  }

  @Test
  void escaladerTraceToutPrevientLeNiveauSuperieurEtNeMontePasDeuxFois() throws Exception {
    Alert alert = alert(null, Severity.CRITICAL);
    call(alert.getId() + "/prise-en-charge", n1Jwt, "{}").andExpect(status().isOk());
    mockMvc
        .perform(
            get("/api/v1/alertes/" + alert.getId() + "/escalade")
                .header("Authorization", "Bearer " + n1Jwt))
        .andExpect(jsonPath("$.toLevel").value(2))
        .andExpect(
            jsonPath("$.candidates[*].username")
                .value(org.hamcrest.Matchers.hasItem(n2.getUsername())));

    String body =
        "{\"checks\":[\"SERVICE_RESTARTED\",\"OUT_OF_MY_RIGHTS\"],"
            + "\"reason\":\"Le service redémarre puis retombe, il faut un accès root\","
            + "\"expectedLevel\":1}";
    call(alert.getId() + "/escalade", n1Jwt, body)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("OPEN"))
        .andExpect(jsonPath("$.currentLevel").value(2));
    // Double clic : l'alerte n'est plus au niveau 1, rien ne monte une seconde fois.
    call(alert.getId() + "/escalade", n1Jwt, body).andExpect(status().isForbidden());
    call(alert.getId() + "/escalade", n2Jwt, body).andExpect(status().isConflict());

    em.flush();
    var line =
        jdbc.queryForMap(
            "SELECT ev.from_level, ev.to_level, ev.checklist, ev.recipients, ev.comment,"
                + " u.username FROM alert_event ev JOIN app_user u ON u.id = ev.author_id"
                + " WHERE ev.alert_id = ? AND ev.type = 'LEVEL_ESCALATED'",
            alert.getId());
    assertThat(line.get("from_level")).isEqualTo(1);
    assertThat(line.get("to_level")).isEqualTo(2);
    assertThat(line.get("checklist")).isEqualTo("SERVICE_RESTARTED,OUT_OF_MY_RIGHTS");
    assertThat(line.get("recipients")).isEqualTo(n2.getUsername());
    assertThat(line.get("username")).isEqualTo(n1.getUsername());
    // Prévenu même sans préférence : par e-mail, avec le modèle d'astreinte et le préfixe.
    em.flush();
    assertThat(
            jdbc.queryForObject(
                "SELECT subject FROM notification WHERE alert_id = ? AND user_id = ?",
                String.class,
                alert.getId(),
                n2.getId()))
        .startsWith("[Escalade vers N2]");
    assertThat(
            count(
                "SELECT count(*) FROM audit_entry WHERE action = 'ALERT_LEVEL_ESCALATED' AND"
                    + " target_id = ?",
                alert.getId()))
        .isEqualTo(1);
    // La prise en charge du N1 est relâchée : le N2 peut la prendre.
    call(alert.getId() + "/prise-en-charge", n2Jwt, "{}").andExpect(status().isOk());
  }

  @Test
  void unNiveauVideEstSauteEtSansNiveauSuperieurCeSontLesAdministrateurs() throws Exception {
    jdbc.update("UPDATE app_user SET support_level = 3 WHERE id = ?", n2.getId());
    em.clear();
    Alert alert = alert(1, Severity.WARNING);
    call(alert.getId() + "/escalade", n1Jwt, "{\"reason\":\"Hors de mes droits\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.currentLevel").value(3));

    Alert top = alert(3, Severity.WARNING);
    call(top.getId() + "/escalade", n2Jwt, "{\"reason\":\"Il faut l'administrateur\"}")
        .andExpect(status().isOk());
    em.flush();
    assertThat(
            jdbc.queryForObject(
                "SELECT recipients FROM alert_event WHERE alert_id = ? AND type ="
                    + " 'LEVEL_ESCALATED'",
                String.class,
                top.getId()))
        .isEqualTo(admin.getUsername());
    // Escaladée aux administrateurs : plus aucun opérateur ne la prend.
    call(top.getId() + "/prise-en-charge", n2Jwt, "{}").andExpect(status().isForbidden());
    // L'administrateur ne peut pas monter plus haut.
    call(top.getId() + "/escalade", adminJwt, "{\"reason\":\"Encore\"}")
        .andExpect(status().isConflict());
  }

  @Test
  void leDestinataireChoisiDoitEtreDuNiveauViseEtLesCasesConnues() throws Exception {
    Alert alert = alert(1, Severity.WARNING);
    call(
            alert.getId() + "/escalade",
            n1Jwt,
            "{\"reason\":\"x\",\"recipientId\":\"" + n1.getId() + "\"}")
        .andExpect(status().isBadRequest());
    call(alert.getId() + "/escalade", n1Jwt, "{\"reason\":\"x\",\"checks\":[\"INCONNUE\"]}")
        .andExpect(status().isBadRequest());
    call(alert.getId() + "/escalade", n1Jwt, "{\"reason\":\" \"}")
        .andExpect(status().isBadRequest());
    call(
            alert.getId() + "/escalade",
            n1Jwt,
            "{\"reason\":\"Avis de l'administrateur\",\"recipientId\":\"" + admin.getId() + "\"}")
        .andExpect(status().isOk());
    em.flush();
    assertThat(
            jdbc.queryForObject(
                "SELECT recipients FROM alert_event WHERE alert_id = ? AND type ="
                    + " 'LEVEL_ESCALATED'",
                String.class,
                alert.getId()))
        .isEqualTo(admin.getUsername());
  }

  @Test
  void renvoyerAuNiveauInferieurAvecUneConsigne() throws Exception {
    Alert alert = alert(2, Severity.WARNING);
    call(alert.getId() + "/renvoi", n1Jwt, "{\"instruction\":\"x\"}")
        .andExpect(status().isForbidden());
    call(
            alert.getId() + "/renvoi",
            n2Jwt,
            "{\"instruction\":\"Redémarre nginx puis surveille 10 min\",\"expectedLevel\":2}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.currentLevel").value(1));
    em.flush();
    var line =
        jdbc.queryForMap(
            "SELECT from_level, to_level, comment, recipients FROM alert_event WHERE alert_id = ?"
                + " AND type = 'SENT_BACK'",
            alert.getId());
    assertThat(line.get("comment")).isEqualTo("Redémarre nginx puis surveille 10 min");
    assertThat(line.get("recipients")).isEqualTo(n1.getUsername());
    // Plus rien en dessous du N1.
    call(alert.getId() + "/renvoi", n1Jwt, "{\"instruction\":\"x\"}")
        .andExpect(status().isConflict());
  }

  @Test
  void leJournalRecoitLesNotesEtLeLecteurNEcritRien() throws Exception {
    Alert alert = alert(1, Severity.WARNING);
    call(alert.getId() + "/notes", n1Jwt, "{\"text\":\"Câble du port 12 reposé\"}")
        .andExpect(status().isCreated());
    call(alert.getId() + "/notes", viewerJwt, "{\"text\":\"x\"}").andExpect(status().isForbidden());
    call(alert.getId() + "/escalade", viewerJwt, "{\"reason\":\"x\"}")
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            get("/api/v1/alertes/" + alert.getId()).header("Authorization", "Bearer " + viewerJwt))
        .andExpect(
            jsonPath("$.events[?(@.type == 'NOTE')].comment")
                .value(org.hamcrest.Matchers.contains("Câble du port 12 reposé")))
        .andExpect(
            jsonPath("$.events[?(@.type == 'NOTE')].author")
                .value(org.hamcrest.Matchers.contains(n1.getUsername())));
  }

  @Test
  void uneAlerteCritiquePeutMonterDOfficeAuPlusHautNiveauOccupe() {
    Alert alert = alert(null, Severity.CRITICAL);
    assertThat(levels.raiseToTop(alert.getId())).isTrue();
    em.flush();
    em.refresh(alert);
    assertThat(alert.getCurrentLevel()).isEqualTo(2);
    em.flush();
    assertThat(
            jdbc.queryForObject(
                "SELECT recipients FROM alert_event WHERE alert_id = ? AND type ="
                    + " 'LEVEL_ESCALATED'",
                String.class,
                alert.getId()))
        .isEqualTo(n2.getUsername());
    // Déjà au plus haut : rien ne change.
    assertThat(levels.raiseToTop(alert.getId())).isFalse();
  }

  @Test
  void lesIndicateursDonnentLeTauxEtLeDelaiDEscaladeParNiveau() throws Exception {
    Alert escalated = alert(1, Severity.WARNING);
    alert(1, Severity.WARNING);
    call(escalated.getId() + "/escalade", n1Jwt, "{\"reason\":\"Besoin du N2\"}")
        .andExpect(status().isOk());
    em.flush();
    mockMvc
        .perform(
            get("/api/v1/indicateurs/service")
                .header("Authorization", "Bearer " + adminJwt)
                .param("from", Instant.now().minusSeconds(3600).toString())
                .param("to", Instant.now().plusSeconds(60).toString())
                .param("siteId", site.getId().toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.escalations.escalated").value(1))
        .andExpect(jsonPath("$.escalations.ratePercent").value(50.0))
        .andExpect(jsonPath("$.escalations.byLevel[0].fromLevel").value(1))
        .andExpect(jsonPath("$.escalations.byLevel[0].escalations").value(1));
  }
}

package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
 * Proxy de collecte d'un site et tampon hors ligne (ADR 0047), de l'enregistrement au rejeu d'un
 * lot.
 *
 * <p>Ce qui est éprouvé ici n'est pas que la réception « marche », mais les trois choix qui la
 * rendent utilisable : un lot n'échoue jamais en entier pour une mesure fautive, un rejeu ne compte
 * pas deux fois, et une mesure vieille de six heures remplit l'historique sans réveiller
 * l'astreinte.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProxyApiTests {
  private static final String PASSWORD = "Mot-de-passe-test-123";

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private PasswordEncoder encoder;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private JdbcTemplate jdbc;

  private Site site;
  private Site other;
  private Equipment equipment;
  private Equipment elsewhere;
  private String adminJwt;
  private String viewerJwt;

  @BeforeEach
  void data() throws Exception {
    users.deleteAll();
    users.save(
        new AppUser(
            "proxy-admin", "proxy-admin@example.test", encoder.encode(PASSWORD), Role.ADMIN));
    users.save(
        new AppUser(
            "proxy-viewer", "proxy-viewer@example.test", encoder.encode(PASSWORD), Role.VIEWER));
    adminJwt = login("proxy-admin");
    viewerJwt = login("proxy-viewer");

    String unique = UUID.randomUUID().toString();
    site = sites.save(new Site("PRX-" + unique.substring(0, 8), "Site de proxy"));
    other = sites.save(new Site("PRY-" + unique.substring(0, 8), "Autre site"));
    equipment = equipments.save(equipment("PRX-EQ-" + unique, site, 1));
    elsewhere = equipments.save(equipment("PRY-EQ-" + unique, other, 2));
  }

  private static Equipment equipment(String code, Site site, int offset) {
    return new Equipment(
        code,
        "Commutateur relayé",
        "10.244." + offset + "." + (Math.floorMod(code.hashCode(), 250) + 1),
        EquipmentType.SWITCH,
        EquipmentStatus.UP,
        site);
  }

  /** Ce test valide des données réelles (pas de transaction) : il les retire pour les autres. */
  @AfterEach
  void cleanUp() {
    for (Equipment e : new Equipment[] {equipment, elsewhere}) {
      jdbc.update(
          "DELETE FROM alert_event WHERE alert_id IN (SELECT id FROM alert WHERE equipment_id = ?)",
          e.getId());
      jdbc.update("DELETE FROM alert WHERE equipment_id = ?", e.getId());
      jdbc.update("DELETE FROM metric WHERE equipment_id = ?", e.getId());
      jdbc.update("DELETE FROM equipment WHERE id = ?", e.getId());
    }
    jdbc.update(
        "DELETE FROM proxy_batch WHERE proxy_id IN (SELECT id FROM collection_proxy WHERE site_id IN (?, ?))",
        site.getId(),
        other.getId());
    jdbc.update(
        "DELETE FROM collection_proxy WHERE site_id IN (?, ?)", site.getId(), other.getId());
    jdbc.update("DELETE FROM audit_entry WHERE target_id IN (?, ?)", site.getId(), other.getId());
    jdbc.update("DELETE FROM site WHERE id IN (?, ?)", site.getId(), other.getId());
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

  private String register() throws Exception {
    var body =
        mockMvc
            .perform(
                post("/api/v1/sites/" + site.getId() + "/proxy")
                    .header("Authorization", "Bearer " + adminJwt))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.token").isNotEmpty())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(body, "$.token");
  }

  private static String lot(UUID lotId, UUID equipmentId, String value, Instant at) {
    return "{\"lotId\":\""
        + lotId
        + "\",\"equipements\":[{\"equipementId\":\""
        + equipmentId
        + "\",\"mesures\":[{\"key\":\"cpu.usage\",\"value\":"
        + value
        + ",\"unit\":\"%\",\"quality\":\"GOOD\",\"collectedAt\":\""
        + at
        + "\"}]}]}";
  }

  private ResultActions send(String token, String json) throws Exception {
    var request =
        post("/api/v1/proxy/measures").contentType(MediaType.APPLICATION_JSON).content(json);
    if (token != null) {
      request = request.header("Authorization", "Bearer " + token);
    }
    return mockMvc.perform(request);
  }

  private int stored(Equipment e) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM metric WHERE equipment_id = ?", Integer.class, e.getId());
  }

  @Test
  @DisplayName("Ce que le proxy apporte : un seul jeton pour tous les équipements du site")
  void whatTheProxyAddsOnetokenForEveryEquipmentOfTheSite() throws Exception {
    var second = equipments.save(equipment("PRX-EQ2-" + UUID.randomUUID(), site, 3));
    String token = register();
    Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    String json =
        "{\"lotId\":\""
            + UUID.randomUUID()
            + "\",\"equipements\":["
            + "{\"equipementId\":\""
            + equipment.getId()
            + "\",\"mesures\":[{\"key\":\"cpu.usage\",\"value\":11,\"unit\":\"%\",\"collectedAt\":\""
            + now
            + "\"}]},"
            + "{\"equipementId\":\""
            + second.getId()
            + "\",\"mesures\":[{\"key\":\"cpu.usage\",\"value\":12,\"unit\":\"%\",\"collectedAt\":\""
            + now
            + "\"}]}]}";

    send(token, json)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.stockees").value(2))
        .andExpect(jsonPath("$.evaluees").value(2));

    assertThat(stored(equipment)).isEqualTo(1);
    assertThat(stored(second)).isEqualTo(1);
    jdbc.update("DELETE FROM metric WHERE equipment_id = ?", second.getId());
    jdbc.update("DELETE FROM equipment WHERE id = ?", second.getId());
  }

  @Test
  @DisplayName("Ce qui compte le plus : un tampon rejoué remplit l'historique sans ouvrir d'alerte")
  void whatMattersMostAreplayedBufferFillsHistoryWithoutOpeningAnalert() throws Exception {
    String token = register();
    // Six heures de tampon : la panne est terminée, personne ne doit être réveillé pour elle.
    Instant old = Instant.now().minus(Duration.ofHours(6)).truncatedTo(ChronoUnit.SECONDS);

    send(token, lot(UUID.randomUUID(), equipment.getId(), "99", old))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.stockees").value(1))
        .andExpect(jsonPath("$.evaluees").value(0))
        .andExpect(jsonPath("$.alertesOuvertes").value(0));

    // La mesure est bien là : elle sert à l'historique, aux rapports et à l'apprentissage.
    assertThat(stored(equipment)).isEqualTo(1);
  }

  @Test
  @DisplayName("Une mesure récente du même lot est, elle, évaluée")
  void arecentMeasureOfTheSameLotIsEvaluated() throws Exception {
    String token = register();
    Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    Instant old = now.minus(Duration.ofHours(6));

    String json =
        "{\"lotId\":\""
            + UUID.randomUUID()
            + "\",\"equipements\":[{\"equipementId\":\""
            + equipment.getId()
            + "\",\"mesures\":["
            + "{\"key\":\"cpu.usage\",\"value\":42,\"unit\":\"%\",\"collectedAt\":\""
            + old
            + "\"},"
            + "{\"key\":\"cpu.usage\",\"value\":43,\"unit\":\"%\",\"collectedAt\":\""
            + now
            + "\"}]}]}";

    // L'état d'aujourd'hui est dans la dernière mesure du tampon : une panne encore en cours doit
    // ressortir, sans quoi le rattrapage masquerait le présent.
    send(token, json)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.stockees").value(2))
        .andExpect(jsonPath("$.evaluees").value(1));
  }

  @Test
  @DisplayName("Un lot rejoué ne compte pas deux fois")
  void areplayedLotDoesNotCountTwice() throws Exception {
    String token = register();
    UUID lotId = UUID.randomUUID();
    Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    String json = lot(lotId, equipment.getId(), "20", now);

    send(token, json).andExpect(status().isOk()).andExpect(jsonPath("$.rejeu").value(false));
    // Même numéro de lot : un accusé de réception perdu ne doit pas doubler les mesures.
    send(token, json)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rejeu").value(true))
        .andExpect(jsonPath("$.stockees").value(1));

    assertThat(stored(equipment)).isEqualTo(1);
  }

  @Test
  @DisplayName("Une mesure hors de la fenêtre de rattrapage est écartée, le lot passe quand même")
  void ameasureBeyondTheBackfillWindowIsDroppedAndTheLotStillPasses() throws Exception {
    String token = register();
    Instant ancient = Instant.now().minus(Duration.ofDays(3)).truncatedTo(ChronoUnit.SECONDS);
    Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    String json =
        "{\"lotId\":\""
            + UUID.randomUUID()
            + "\",\"equipements\":[{\"equipementId\":\""
            + equipment.getId()
            + "\",\"mesures\":["
            + "{\"key\":\"cpu.usage\",\"value\":5,\"unit\":\"%\",\"collectedAt\":\""
            + ancient
            + "\"},"
            + "{\"key\":\"cpu.usage\",\"value\":6,\"unit\":\"%\",\"collectedAt\":\""
            + now
            + "\"}]}]}";

    // Refuser le lot entier gèlerait la collecte du site : le proxy renverrait indéfiniment la
    // même mesure trop vieille.
    send(token, json)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tropVieilles").value(1))
        .andExpect(jsonPath("$.stockees").value(1));

    assertThat(stored(equipment)).isEqualTo(1);
  }

  @Test
  @DisplayName("Le jeton d'un proxy ne vaut que pour son site")
  void aproxyTokenIsWorthNothingOutsideItsSite() throws Exception {
    String token = register();
    Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    // Volé dans un local technique, un jeton de proxy ne doit pas permettre d'écrire sur tout le
    // campus.
    send(token, lot(UUID.randomUUID(), elsewhere.getId(), "77", now))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.horsSite").value(1))
        .andExpect(jsonPath("$.stockees").value(0));

    assertThat(stored(elsewhere)).isZero();
  }

  @Test
  @DisplayName("Sans jeton, ou avec un jeton révoqué, rien n'entre")
  void withoutAtokenOrWithArevokedOneNothingGetsIn() throws Exception {
    Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    String json = lot(UUID.randomUUID(), equipment.getId(), "30", now);

    send(null, json).andExpect(status().isUnauthorized());
    send("tdic1_inconnu", json).andExpect(status().isUnauthorized());

    String token = register();
    mockMvc
        .perform(
            delete("/api/v1/sites/" + site.getId() + "/proxy")
                .header("Authorization", "Bearer " + adminJwt))
        .andExpect(status().isNoContent());
    send(token, json).andExpect(status().isUnauthorized());

    assertThat(stored(equipment)).isZero();
  }

  @Test
  @DisplayName("Enregistrer à nouveau révoque le jeton précédent")
  void registeringAgainRevokesTheFormerToken() throws Exception {
    String first = register();
    String second = register();
    Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    assertThat(first).isNotEqualTo(second);
    send(first, lot(UUID.randomUUID(), equipment.getId(), "1", now))
        .andExpect(status().isUnauthorized());
    send(second, lot(UUID.randomUUID(), equipment.getId(), "2", now)).andExpect(status().isOk());
  }

  @Test
  @DisplayName("Un administrateur lit l'état du proxy, et le jeton n'y est plus")
  void anadministratorReadsTheProxyStatusWithoutTheToken() throws Exception {
    register();

    mockMvc
        .perform(
            get("/api/v1/sites/" + site.getId() + "/proxy")
                .header("Authorization", "Bearer " + adminJwt))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.identifier").isNotEmpty())
        .andExpect(jsonPath("$.status").value("ACTIVE"))
        .andExpect(jsonPath("$.silencieux").value(false))
        .andExpect(jsonPath("$.token").doesNotExist());
  }

  @Test
  @DisplayName("Un lecteur n'enregistre pas de proxy")
  void aviewerDoesNotRegisterAproxy() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/sites/" + site.getId() + "/proxy")
                .header("Authorization", "Bearer " + viewerJwt))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("Un lot vide est refusé")
  void anemptyLotIsRefused() throws Exception {
    String token = register();

    send(token, "{\"lotId\":\"" + UUID.randomUUID() + "\",\"equipements\":[]}")
        .andExpect(status().isBadRequest());
  }
}

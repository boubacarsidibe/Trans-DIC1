package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
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
 * Inscription automatique des agents par jeton partagé (ADR 0054), de la création du jeton à
 * l'envoi des premières mesures par l'agent inscrit.
 *
 * <p>Ce qui compte ici : un jeton d'inscription n'ouvre rien d'autre que l'inscription, une machine
 * ne crée jamais deux équipements, et chaque garde-fou du jeton (expiration, révocation, compteur,
 * plages d'adresses) refuse et laisse une trace.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AgentEnrollmentApiTests {
  private static final String PASSWORD = "Mot-de-passe-test-123";
  private static final SecureRandom RANDOM = new SecureRandom();

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private PasswordEncoder encoder;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private JdbcTemplate jdbc;

  private Site site;
  private Site other;
  private String adminJwt;
  private String viewerJwt;

  @BeforeEach
  void data() throws Exception {
    users.deleteAll();
    users.save(
        new AppUser(
            "enroll-admin", "enroll-admin@example.test", encoder.encode(PASSWORD), Role.ADMIN));
    users.save(
        new AppUser(
            "enroll-viewer", "enroll-viewer@example.test", encoder.encode(PASSWORD), Role.VIEWER));
    adminJwt = login("enroll-admin");
    viewerJwt = login("enroll-viewer");
    String unique = UUID.randomUUID().toString().substring(0, 8);
    site = sites.save(new Site("INS-" + unique, "Site d'inscription"));
    other = sites.save(new Site("INT-" + unique, "Autre site"));
  }

  /** Ce test valide des données réelles (pas de transaction) : il les retire pour les autres. */
  @AfterEach
  void cleanUp() {
    for (Site s : new Site[] {site, other}) {
      List<UUID> targets = new ArrayList<>();
      targets.addAll(
          jdbc.queryForList("SELECT id FROM equipment WHERE site_id = ?", UUID.class, s.getId()));
      targets.addAll(
          jdbc.queryForList(
              "SELECT id FROM enrollment_token WHERE site_id = ?", UUID.class, s.getId()));
      targets.addAll(
          jdbc.queryForList(
              "SELECT id FROM agent_enrollment WHERE site_id = ?", UUID.class, s.getId()));
      String inSite = "(SELECT id FROM equipment WHERE site_id = ?)";
      jdbc.update(
          "DELETE FROM alert_event WHERE alert_id IN (SELECT id FROM alert WHERE equipment_id IN "
              + inSite
              + ")",
          s.getId());
      jdbc.update("DELETE FROM alert WHERE equipment_id IN " + inSite, s.getId());
      jdbc.update("DELETE FROM metric WHERE equipment_id IN " + inSite, s.getId());
      jdbc.update("DELETE FROM collection_agent WHERE equipment_id IN " + inSite, s.getId());
      jdbc.update("DELETE FROM agent_enrollment WHERE site_id = ?", s.getId());
      jdbc.update("DELETE FROM equipment WHERE site_id = ?", s.getId());
      jdbc.update("DELETE FROM enrollment_token WHERE site_id = ?", s.getId());
      for (UUID target : targets) {
        jdbc.update("DELETE FROM audit_entry WHERE target_id = ?", target);
      }
      jdbc.update("DELETE FROM site WHERE id = ?", s.getId());
    }
  }

  // --- Outils
  // -------------------------------------------------------------------------------------

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

  private static String tokenJson(
      UUID siteId, String mode, int maxUses, String ranges, String type, Instant expires) {
    return "{\"nom\":\"Vague de test\",\"siteId\":\""
        + siteId
        + "\",\"expireLe\":\""
        + expires
        + "\",\"utilisationsMax\":"
        + maxUses
        + ",\"plagesAutorisees\":"
        + ranges
        + ",\"mode\":\""
        + mode
        + "\",\"typeEquipement\":\""
        + type
        + "\"}";
  }

  private ResultActions postToken(String json) throws Exception {
    return mockMvc.perform(
        post("/api/v1/inscriptions/jetons")
            .header("Authorization", "Bearer " + adminJwt)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json));
  }

  private String createToken(String mode, int maxUses, String ranges) throws Exception {
    var body =
        postToken(
                tokenJson(
                    site.getId(),
                    mode,
                    maxUses,
                    ranges,
                    "SERVER",
                    Instant.now().plus(1, ChronoUnit.DAYS)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(body, "$.jeton");
  }

  private UUID tokenId(String token) {
    return jdbc.queryForObject(
        "SELECT id FROM enrollment_token WHERE site_id = ? ORDER BY created_at DESC LIMIT 1",
        UUID.class,
        site.getId());
  }

  private static String machineId() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return HexFormat.of().formatHex(bytes);
  }

  private ResultActions enroll(
      String token, String machineId, String hostname, String ip, String source) throws Exception {
    return mockMvc.perform(
        post("/api/v1/agent/enroll")
            .with(
                request -> {
                  request.setRemoteAddr(source);
                  return request;
                })
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"hostname\":\""
                    + hostname
                    + "\",\"machineId\":\""
                    + machineId
                    + "\",\"ipAddresses\":[\"127.0.0.1\",\""
                    + ip
                    + "\"],\"os\":\"Linux\",\"osVersion\":\"Debian 12\",\"agentVersion\":\"2.1.0\"}"));
  }

  private String enrolledToken(ResultActions result) throws Exception {
    String body = result.andReturn().getResponse().getContentAsString();
    return JsonPath.read(body, "$.jeton");
  }

  private ResultActions sendMeasures(String token) throws Exception {
    return mockMvc.perform(
        post("/api/v1/agent/measures")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"measures\":[{\"key\":\"cpu.usage\",\"value\":12.5,\"unit\":\"%\","
                    + "\"quality\":\"GOOD\",\"collectedAt\":\""
                    + Instant.now().truncatedTo(ChronoUnit.SECONDS)
                    + "\"}]}"));
  }

  private int equipmentsInSite() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM equipment WHERE site_id = ?", Integer.class, site.getId());
  }

  private int audits(String action) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM audit_entry WHERE action = ? AND target_id IN"
            + " (SELECT id FROM enrollment_token WHERE site_id = ?"
            + " UNION SELECT id FROM agent_enrollment WHERE site_id = ?"
            + " UNION SELECT id FROM equipment WHERE site_id = ?)",
        Integer.class,
        action,
        site.getId(),
        site.getId(),
        site.getId());
  }

  // --- Administration des jetons ----------------------------------------------------------------

  @Test
  @DisplayName("Le jeton d'inscription n'est montré qu'une fois : seule son empreinte est stockée")
  void tokenIsShownOnceAndOnlyItsHashIsStored() throws Exception {
    String token = createToken("AUTO", 10, "[\"192.0.2.0/24\"]");

    assertThat(token).startsWith("tdic1e_").hasSizeGreaterThan(40);
    String list =
        mockMvc
            .perform(
                get("/api/v1/inscriptions/jetons").header("Authorization", "Bearer " + adminJwt))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].etat").value("ACTIF"))
            .andExpect(jsonPath("$[0].plagesAutorisees[0]").value("192.0.2.0/24"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(list).doesNotContain(token);
    String stored =
        jdbc.queryForObject(
            "SELECT token_hash FROM enrollment_token WHERE id = ?", String.class, tokenId(token));
    assertThat(stored).isNotEqualTo(token).hasSize(64);
    assertThat(audits("ENROLLMENT_TOKEN_CREATED")).isEqualTo(1);
  }

  @Test
  @DisplayName("Seul un administrateur gère les jetons et les demandes d'inscription")
  void onlyAnAdministratorManagesEnrollment() throws Exception {
    mockMvc
        .perform(get("/api/v1/inscriptions/jetons").header("Authorization", "Bearer " + viewerJwt))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            post("/api/v1/inscriptions/jetons")
                .header("Authorization", "Bearer " + viewerJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    tokenJson(
                        site.getId(),
                        "AUTO",
                        1,
                        "[]",
                        "SERVER",
                        Instant.now().plus(1, ChronoUnit.DAYS))))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            get("/api/v1/inscriptions/demandes").header("Authorization", "Bearer " + viewerJwt))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("Un jeton mal formé est refusé : date passée, type sans agent, plage invalide")
  void invalidTokenRequestsAreRefused() throws Exception {
    Instant tomorrow = Instant.now().plus(1, ChronoUnit.DAYS);
    postToken(
            tokenJson(
                site.getId(), "AUTO", 5, "[]", "SERVER", Instant.now().minus(1, ChronoUnit.HOURS)))
        .andExpect(status().isBadRequest());
    postToken(
            tokenJson(
                site.getId(), "AUTO", 5, "[]", "SERVER", Instant.now().plus(400, ChronoUnit.DAYS)))
        .andExpect(status().isBadRequest());
    postToken(tokenJson(site.getId(), "AUTO", 5, "[]", "ROUTER", tomorrow))
        .andExpect(status().isBadRequest());
    postToken(tokenJson(site.getId(), "AUTO", 5, "[\"192.0.2.1/24\"]", "SERVER", tomorrow))
        .andExpect(status().isBadRequest());
    postToken(tokenJson(site.getId(), "AUTO", 0, "[]", "SERVER", tomorrow))
        .andExpect(status().isBadRequest());
  }

  // --- Mode AUTO
  // ----------------------------------------------------------------------------------

  @Test
  @DisplayName("Critère du lot : trois machines, un même jeton, trois équipements qui mesurent")
  void threeMachinesWithOneTokenGiveThreeEquipmentsThatSendMeasures() throws Exception {
    String token = createToken("AUTO", 100, "[]");

    for (int i = 1; i <= 3; i++) {
      var result =
          enroll(token, machineId(), "srv-test-0" + i, "192.0.2." + (10 + i), "192.0.2." + (10 + i))
              .andExpect(status().isCreated())
              .andExpect(jsonPath("$.statut").value("INSCRIT"))
              .andExpect(jsonPath("$.equipementId").isNotEmpty());
      String agentToken = enrolledToken(result);
      assertThat(agentToken).startsWith("tdic1_").doesNotStartWith("tdic1e_");
      sendMeasures(agentToken).andExpect(status().isOk());
    }

    assertThat(equipmentsInSite()).isEqualTo(3);
    var created =
        jdbc.queryForList(
            "SELECT name, equipment_type, operational_status, management_address, operating_system"
                + " FROM equipment WHERE site_id = ? ORDER BY name",
            site.getId());
    assertThat(created.get(0).get("name")).isEqualTo("srv-test-01");
    assertThat(created.get(0).get("equipment_type")).isEqualTo("SERVER");
    // La première mesure fait passer l'équipement de « inconnu » à « en service ».
    assertThat(created)
        .allSatisfy(row -> assertThat(row.get("operational_status")).isEqualTo("UP"));
    // L'adresse de boucle locale déclarée par l'agent n'est jamais retenue.
    assertThat(created.get(0).get("management_address")).isEqualTo("192.0.2.11");
    assertThat(created.get(0).get("operating_system")).isEqualTo("Linux Debian 12");
    assertThat(audits("AGENT_ENROLLED")).isEqualTo(3);
  }

  @Test
  @DisplayName("Idempotence : la même machine inscrite deux fois garde un seul équipement")
  void theSameMachineTwiceKeepsOneEquipmentAndRevokesTheFirstToken() throws Exception {
    String token = createToken("AUTO", 10, "[]");
    String machine = machineId();

    var first =
        enroll(token, machine, "srv-idem", "192.0.2.20", "192.0.2.20")
            .andExpect(status().isCreated());
    String firstToken = enrolledToken(first);
    String firstEquipment =
        JsonPath.read(first.andReturn().getResponse().getContentAsString(), "$.equipementId");
    var second =
        enroll(token, machine, "srv-idem", "192.0.2.20", "192.0.2.20")
            .andExpect(status().isCreated());
    String secondToken = enrolledToken(second);

    assertThat(equipmentsInSite()).isEqualTo(1);
    assertThat(
            (String)
                JsonPath.read(
                    second.andReturn().getResponse().getContentAsString(), "$.equipementId"))
        .isEqualTo(firstEquipment);
    // Exactement comme un nouvel enregistrement manuel : l'ancien jeton ne vaut plus rien.
    sendMeasures(firstToken).andExpect(status().isUnauthorized());
    sendMeasures(secondToken).andExpect(status().isOk());
    assertThat(
            jdbc.queryForObject(
                "SELECT use_count FROM enrollment_token WHERE id = ?",
                Integer.class,
                tokenId(token)))
        .isEqualTo(2);
  }

  @Test
  @DisplayName("Un équipement déjà inventorié à l'adresse de la connexion est adopté, pas doublé")
  void anEquipmentAlreadyInventoriedAtTheSourceAddressIsAdopted() throws Exception {
    var existing =
        equipments.save(
            new Equipment(
                "CMDB-" + UUID.randomUUID().toString().substring(0, 8),
                "srv-cmdb",
                "192.0.2.50",
                EquipmentType.SERVER,
                EquipmentStatus.UNKNOWN,
                site));
    String token = createToken("AUTO", 10, "[]");

    enroll(token, machineId(), "srv-cmdb", "192.0.2.50", "192.0.2.50")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.equipementId").value(existing.getId().toString()));

    assertThat(equipmentsInSite()).isEqualTo(1);
  }

  // --- Garde-fous du jeton ----------------------------------------------------------------------

  @Test
  @DisplayName("Un jeton expiré est refusé, et le refus est tracé avec sa raison")
  void anExpiredTokenIsRefusedAndAudited() throws Exception {
    String token = createToken("AUTO", 10, "[]");
    jdbc.update(
        "UPDATE enrollment_token SET expires_at = now() - interval '1 minute' WHERE id = ?",
        tokenId(token));

    enroll(token, machineId(), "srv-expire", "192.0.2.30", "192.0.2.30")
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.statut").value("REFUSE"));

    assertThat(equipmentsInSite()).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT details_sanitized FROM audit_entry WHERE action = 'AGENT_ENROLLMENT_REFUSED'"
                    + " AND target_id = ?",
                String.class,
                tokenId(token)))
        .contains("raison=jeton expiré");
  }

  @Test
  @DisplayName("Un jeton révoqué est refusé ; les agents déjà inscrits continuent")
  void aRevokedTokenIsRefusedButEnrolledAgentsKeepWorking() throws Exception {
    String token = createToken("AUTO", 10, "[]");
    String agentToken =
        enrolledToken(
            enroll(token, machineId(), "srv-avant", "192.0.2.40", "192.0.2.40")
                .andExpect(status().isCreated()));

    mockMvc
        .perform(
            delete("/api/v1/inscriptions/jetons/" + tokenId(token))
                .header("Authorization", "Bearer " + adminJwt))
        .andExpect(status().isNoContent());

    enroll(token, machineId(), "srv-apres", "192.0.2.41", "192.0.2.41")
        .andExpect(status().isUnauthorized());
    sendMeasures(agentToken).andExpect(status().isOk());
    assertThat(audits("ENROLLMENT_TOKEN_REVOKED")).isEqualTo(1);
    assertThat(audits("AGENT_ENROLLMENT_REFUSED")).isEqualTo(1);
  }

  @Test
  @DisplayName("Au-delà du nombre maximal d'utilisations, plus aucune inscription")
  void theUsageCounterStopsEnrollment() throws Exception {
    String token = createToken("AUTO", 2, "[]");
    String machine = machineId();

    enroll(token, machine, "srv-a", "192.0.2.60", "192.0.2.60").andExpect(status().isCreated());
    enroll(token, machineId(), "srv-b", "192.0.2.61", "192.0.2.61").andExpect(status().isCreated());
    enroll(token, machineId(), "srv-c", "192.0.2.62", "192.0.2.62")
        .andExpect(status().isUnauthorized());
    // Une réinscription consomme aussi une utilisation.
    enroll(token, machine, "srv-a", "192.0.2.60", "192.0.2.60")
        .andExpect(status().isUnauthorized());

    assertThat(equipmentsInSite()).isEqualTo(2);
  }

  @Test
  @DisplayName("Une adresse source hors des plages autorisées est refusée")
  void aSourceOutsideTheAllowedRangesIsForbidden() throws Exception {
    String token = createToken("AUTO", 10, "[\"192.0.2.0/24\",\"2001:db8::/32\"]");

    enroll(token, machineId(), "srv-dehors", "198.51.100.7", "198.51.100.7")
        .andExpect(status().isForbidden());
    enroll(token, machineId(), "srv-dedans", "192.0.2.70", "192.0.2.70")
        .andExpect(status().isCreated());
    enroll(token, machineId(), "srv-v6", "2001:db8::70", "2001:db8::70")
        .andExpect(status().isCreated());

    assertThat(equipmentsInSite()).isEqualTo(2);
    assertThat(audits("AGENT_ENROLLMENT_REFUSED")).isEqualTo(1);
  }

  // --- Mode APPROVAL
  // ------------------------------------------------------------------------------

  @Test
  @DisplayName("Mode approbation : 202, l'administrateur approuve, l'agent reçoit son jeton")
  void approvalModeWaitsForAnAdministrator() throws Exception {
    String token = createToken("APPROVAL", 10, "[]");
    String machine = machineId();

    enroll(token, machine, "srv-attente", "192.0.2.80", "192.0.2.80")
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.statut").value("EN_ATTENTE"))
        .andExpect(jsonPath("$.reessayerDansSecondes").isNumber())
        .andExpect(jsonPath("$.jeton").doesNotExist());
    // L'agent redemande : rien de plus n'est consommé, rien n'est créé.
    enroll(token, machine, "srv-attente", "192.0.2.80", "192.0.2.80")
        .andExpect(status().isAccepted());
    assertThat(equipmentsInSite()).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT use_count FROM enrollment_token WHERE id = ?",
                Integer.class,
                tokenId(token)))
        .isEqualTo(1);
    assertThat(audits("AGENT_ENROLLMENT_PENDING")).isEqualTo(1);

    String pending =
        mockMvc
            .perform(
                get("/api/v1/inscriptions/demandes").header("Authorization", "Bearer " + adminJwt))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    List<String> ids = JsonPath.read(pending, "$[?(@.nomHote == 'srv-attente')].id");
    assertThat(ids).hasSize(1);

    mockMvc
        .perform(
            post("/api/v1/inscriptions/demandes/approbation")
                .header("Authorization", "Bearer " + adminJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ids\":[\"" + ids.get(0) + "\"]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.traitees").value(1));
    assertThat(equipmentsInSite()).isEqualTo(1);

    String agentToken =
        enrolledToken(
            enroll(token, machine, "srv-attente", "192.0.2.80", "192.0.2.80")
                .andExpect(status().isCreated()));
    sendMeasures(agentToken).andExpect(status().isOk());
    // Approuver une seconde fois ne refait rien.
    mockMvc
        .perform(
            post("/api/v1/inscriptions/demandes/approbation")
                .header("Authorization", "Bearer " + adminJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ids\":[\"" + ids.get(0) + "\"]}"))
        .andExpect(jsonPath("$.traitees").value(0))
        .andExpect(jsonPath("$.ignorees[0]").value("srv-attente : déjà inscrite"));
  }

  @Test
  @DisplayName("Mode approbation : un refus par lot ferme la porte aux machines refusées")
  void rejectedRequestsAreForbidden() throws Exception {
    String token = createToken("APPROVAL", 10, "[]");
    String first = machineId();
    String second = machineId();
    enroll(token, first, "srv-non-1", "192.0.2.90", "192.0.2.90").andExpect(status().isAccepted());
    enroll(token, second, "srv-non-2", "192.0.2.91", "192.0.2.91").andExpect(status().isAccepted());
    List<String> ids =
        JsonPath.read(
            mockMvc
                .perform(
                    get("/api/v1/inscriptions/demandes")
                        .header("Authorization", "Bearer " + adminJwt))
                .andReturn()
                .getResponse()
                .getContentAsString(),
            "$[?(@.nomHote =~ /srv-non-.*/)].id");

    mockMvc
        .perform(
            post("/api/v1/inscriptions/demandes/refus")
                .header("Authorization", "Bearer " + adminJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ids\":[\"" + ids.get(0) + "\",\"" + ids.get(1) + "\"]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.traitees").value(2));

    enroll(token, first, "srv-non-1", "192.0.2.90", "192.0.2.90").andExpect(status().isForbidden());
    assertThat(equipmentsInSite()).isZero();
    assertThat(audits("AGENT_ENROLLMENT_REJECTED")).isEqualTo(2);
  }

  // --- Cloisonnement des jetons
  // -------------------------------------------------------------------

  @Test
  @DisplayName("Un jeton d'inscription n'ouvre ni l'envoi de mesures ni l'API des utilisateurs")
  void anEnrollmentTokenOpensNothingElse() throws Exception {
    String token = createToken("AUTO", 10, "[]");

    sendMeasures(token).andExpect(status().isUnauthorized());
    mockMvc
        .perform(get("/api/v1/equipements").header("Authorization", "Bearer " + token))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("Un jeton d'agent ne permet pas de s'inscrire, un jeton inconnu non plus")
  void anAgentTokenCannotEnroll() throws Exception {
    String token = createToken("AUTO", 10, "[]");
    String agentToken =
        enrolledToken(
            enroll(token, machineId(), "srv-cloison", "192.0.2.95", "192.0.2.95")
                .andExpect(status().isCreated()));

    enroll(agentToken, machineId(), "srv-intrus", "192.0.2.96", "192.0.2.96")
        .andExpect(status().isUnauthorized());
    enroll("tdic1e_inconnu-0123456789abcdefghij", machineId(), "srv-x", "192.0.2.97", "192.0.2.97")
        .andExpect(status().isUnauthorized());
    // Un JWT d'administrateur n'inscrit pas non plus une machine.
    enroll(adminJwt, machineId(), "srv-y", "192.0.2.98", "192.0.2.98")
        .andExpect(status().isUnauthorized());
    assertThat(equipmentsInSite()).isEqualTo(1);
  }

  @Test
  @DisplayName("Contrat partagé : la demande produite par l'agent est acceptée telle quelle")
  void theRequestProducedByTheAgentIsAcceptedAsIs() throws Exception {
    String token = createToken("AUTO", 10, "[]");
    String fixture =
        java.nio.file.Files.readString(
            java.nio.file.Path.of("..", "agent", "tests", "fixtures", "agent-enroll.json"));

    mockMvc
        .perform(
            post("/api/v1/agent/enroll")
                .with(
                    request -> {
                      request.setRemoteAddr("192.0.2.21");
                      return request;
                    })
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(fixture))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.jeton").isNotEmpty());

    assertThat(
            jdbc.queryForObject(
                "SELECT name FROM equipment WHERE site_id = ?", String.class, site.getId()))
        .isEqualTo("srv-web-01");
  }

  @Test
  @DisplayName("L'enregistrement manuel d'un agent fonctionne toujours")
  void manualRegistrationStillWorks() throws Exception {
    var equipment =
        equipments.save(
            new Equipment(
                "MAN-" + UUID.randomUUID().toString().substring(0, 8),
                "srv-manuel",
                "192.0.2.99",
                EquipmentType.SERVER,
                EquipmentStatus.UNKNOWN,
                site));
    String body =
        mockMvc
            .perform(
                post("/api/v1/equipements/" + equipment.getId() + "/agent")
                    .header("Authorization", "Bearer " + adminJwt))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    sendMeasures(JsonPath.read(body, "$.token")).andExpect(status().isOk());
  }
}

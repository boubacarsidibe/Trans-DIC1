package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.alerting.domain.Alert;
import sn.ept.transdic1.alerting.domain.AlertEventType;
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
import sn.ept.transdic1.reporting.domain.NotificationChannel;
import sn.ept.transdic1.reporting.domain.NotificationPreference;
import sn.ept.transdic1.reporting.repository.NotificationPreferenceRepository;
import sn.ept.transdic1.reporting.service.NotificationDispatchService;
import sn.ept.transdic1.reporting.service.NotificationQueueService;
import sn.ept.transdic1.security.domain.AppUser;
import sn.ept.transdic1.security.domain.Role;
import sn.ept.transdic1.security.repository.UserRepository;

/** Canaux webhook, Teams et Slack contre un faux serveur, idempotence, pannes et administration. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class NotificationChannelTests {
  private static final String PASSWORD = "Mot-de-passe-test-123";
  private static final String SIGNING_SECRET = "secret-de-signature-test";
  private static final HttpServer SERVER = startServer();
  private static final List<Received> RECEIVED = new CopyOnWriteArrayList<>();

  record Received(String path, Map<String, String> headers, String body) {}

  private static HttpServer startServer() {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/",
          exchange -> {
            String body =
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            RECEIVED.add(
                new Received(
                    exchange.getRequestURI().getPath(),
                    Map.of(
                        "X-Signature", header(exchange, "X-Signature"),
                        "X-Timestamp", header(exchange, "X-Timestamp")),
                    body));
            int code = exchange.getRequestURI().getPath().equals("/panne") ? 500 : 200;
            exchange.sendResponseHeaders(code, -1);
            exchange.close();
          });
      server.start();
      return server;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static String header(com.sun.net.httpserver.HttpExchange exchange, String name) {
    String value = exchange.getRequestHeaders().getFirst(name);
    return value == null ? "" : value;
  }

  @AfterAll
  static void stop() {
    SERVER.stop(0);
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    String base = "http://127.0.0.1:" + SERVER.getAddress().getPort();
    registry.add("HOOK_URL", () -> base + "/hook");
    registry.add("TEAMS_URL", () -> base + "/teams");
    registry.add("SLACK_URL", () -> base + "/slack");
    registry.add("PANNE_URL", () -> base + "/panne");
    registry.add("NOTIFICATION_WEBHOOK_SECRET", () -> SIGNING_SECRET);
    registry.add("notification.webhook.allow-insecure", () -> "true");
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private PasswordEncoder encoder;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private AlertRepository alerts;
  @Autowired private NotificationPreferenceRepository preferences;
  @Autowired private NotificationQueueService queue;
  @Autowired private NotificationDispatchService dispatch;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private jakarta.persistence.EntityManager em;

  private String suffix;
  private Alert alert;
  private AppUser operator;
  private String adminJwt;
  private String nocJwt;

  @BeforeEach
  void data() throws Exception {
    RECEIVED.clear();
    jdbc.update("UPDATE notification SET state = 'FAILED' WHERE state = 'PENDING'");
    suffix = UUID.randomUUID().toString().substring(0, 8);
    Site site = sites.save(new Site("CH-" + suffix, "Bâtiment des canaux"));
    Equipment equipment =
        equipments.save(
            new Equipment(
                "CH-EQ-" + suffix,
                "Routeur des canaux",
                "10.237."
                    + (Math.floorMod(suffix.hashCode(), 250) + 1)
                    + "."
                    + (Math.floorMod(new StringBuilder(suffix).reverse().toString().hashCode(), 250)
                        + 1),
                EquipmentType.ROUTER,
                EquipmentStatus.UP,
                site));
    alert =
        alerts.save(
            new Alert(
                equipment,
                "ch-" + suffix,
                AlertType.COLLECTION,
                AlertState.OPEN,
                Severity.CRITICAL,
                "Plus de réponse \"critique\"",
                Instant.now()));
    operator =
        users.save(
            new AppUser("op-" + suffix, "op-" + suffix + "@example.test", "x", Role.NOC_OPERATOR));
    users.save(
        new AppUser(
            "adm-" + suffix,
            "adm-" + suffix + "@example.test",
            encoder.encode(PASSWORD),
            Role.ADMIN));
    users.save(
        new AppUser(
            "noc-" + suffix,
            "noc-" + suffix + "@example.test",
            encoder.encode(PASSWORD),
            Role.NOC_OPERATOR));
    adminJwt = login("adm-" + suffix);
    nocJwt = login("noc-" + suffix);
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

  private void prefer(NotificationChannel channel, String destination) {
    var preference = new NotificationPreference(operator, channel, Severity.INFO);
    preference.setDestination(destination);
    preferences.save(preference);
    em.flush();
  }

  private static String sign(String timestamp, String body) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(SIGNING_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return HexFormat.of()
        .formatHex(mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void leWebhookEstSigneEtContientLeMessage() throws Exception {
    prefer(NotificationChannel.WEBHOOK, "env:HOOK_URL");

    assertThat(queue.queueFor(UUID.randomUUID(), alert.getId(), AlertEventType.OPENED))
        .isEqualTo(1);
    assertThat(dispatch.dispatchPending()).isEqualTo(1);

    var received = RECEIVED.get(0);
    assertThat(received.path()).isEqualTo("/hook");
    assertThat(received.headers().get("X-Signature"))
        .isEqualTo("sha256=" + sign(received.headers().get("X-Timestamp"), received.body()));
    assertThat(received.body()).contains("Routeur des canaux").contains("Bâtiment des canaux");
    assertThat(received.body()).contains("\\\"critique\\\"");
  }

  @Test
  void teamsEtSlackRecoiventLeurFormat() {
    prefer(NotificationChannel.TEAMS, "env:TEAMS_URL");
    queue.queueFor(UUID.randomUUID(), alert.getId(), AlertEventType.OPENED);
    dispatch.dispatchPending();
    assertThat(RECEIVED).hasSize(1);
    assertThat(RECEIVED.get(0).body())
        .contains("application/vnd.microsoft.card.adaptive")
        .contains("Routeur des canaux");

    RECEIVED.clear();
    preferences.findByUserId(operator.getId()).orElseThrow().setChannel(NotificationChannel.SLACK);
    preferences.findByUserId(operator.getId()).orElseThrow().setDestination("env:SLACK_URL");
    em.flush();
    queue.queueFor(UUID.randomUUID(), alert.getId(), AlertEventType.RESOLVED);
    dispatch.dispatchPending();
    assertThat(RECEIVED).hasSize(1);
    assertThat(RECEIVED.get(0).path()).isEqualTo("/slack");
    assertThat(RECEIVED.get(0).body()).startsWith("{\"text\":\"*[CRITIQUE] Alerte résolue");
  }

  @Test
  void lEvenementRejoueNeCreePasDeDoublon() {
    prefer(NotificationChannel.SLACK, "env:SLACK_URL");
    UUID event = UUID.randomUUID();

    assertThat(queue.queueFor(event, alert.getId(), AlertEventType.OPENED)).isEqualTo(1);
    assertThat(queue.queueFor(event, alert.getId(), AlertEventType.OPENED)).isZero();
    // Un autre événement sur la même alerte est un vrai nouveau message.
    assertThat(queue.queueFor(UUID.randomUUID(), alert.getId(), AlertEventType.OPENED))
        .isEqualTo(1);
  }

  @Test
  void unCanalEnPanneEstReessayeEtAbandonneSansFuiteDeLURL() {
    prefer(NotificationChannel.WEBHOOK, "env:PANNE_URL");
    queue.queueFor(UUID.randomUUID(), alert.getId(), AlertEventType.OPENED);

    for (int pass = 0; pass < 5; pass++) {
      assertThat(dispatch.dispatchPending()).isZero();
    }
    em.flush();

    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM notification WHERE alert_id = ?", String.class, alert.getId()))
        .isEqualTo("FAILED");
    var errors =
        jdbc.queryForList(
            "SELECT sanitized_error FROM notification_attempt a JOIN notification n ON n.id ="
                + " a.notification_id WHERE n.alert_id = ?",
            String.class,
            alert.getId());
    assertThat(errors)
        .hasSize(3)
        .allSatisfy(e -> assertThat(e).contains("HTTP 500").doesNotContain("127.0.0.1"));
  }

  @Test
  void uneUrlSaisieALaPlaceDUneReferenceEstRefusee() throws Exception {
    mockMvc
        .perform(
            put("/api/v1/mes-preferences")
                .header("Authorization", "Bearer " + nocJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"channel\":\"TEAMS\",\"minimumSeverity\":\"INFO\",\"active\":true,"
                        + "\"destination\":\"https://exemple.test/webhook/secret\"}"))
        .andExpect(status().isBadRequest());

    mockMvc
        .perform(
            put("/api/v1/mes-preferences")
                .header("Authorization", "Bearer " + nocJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"channel\":\"TEAMS\",\"minimumSeverity\":\"INFO\",\"active\":true,"
                        + "\"destination\":\"env:TEAMS_URL\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.destination").value("env:TEAMS_URL"));
  }

  @Test
  void lAdministrationEstReserveAuxAdministrateurs() throws Exception {
    for (String path : new String[] {"/historique", "/modeles"}) {
      mockMvc
          .perform(get("/api/v1/canaux" + path).header("Authorization", "Bearer " + nocJwt))
          .andExpect(status().isForbidden());
      mockMvc
          .perform(get("/api/v1/canaux" + path).header("Authorization", "Bearer " + adminJwt))
          .andExpect(status().isOk());
    }
    mockMvc
        .perform(
            post("/api/v1/canaux/test")
                .header("Authorization", "Bearer " + nocJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"channel\":\"SLACK\",\"destination\":\"env:SLACK_URL\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void leTestEnvoieUnMessageEtDitSiLeCanalRepond() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/canaux/test")
                .header("Authorization", "Bearer " + adminJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"channel\":\"SLACK\",\"destination\":\"env:SLACK_URL\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true));
    assertThat(RECEIVED).hasSize(1);
    assertThat(RECEIVED.get(0).body()).contains("[TEST]");

    mockMvc
        .perform(
            post("/api/v1/canaux/test")
                .header("Authorization", "Bearer " + adminJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"channel\":\"WEBHOOK\",\"destination\":\"env:PANNE_URL\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("HTTP 500")));
  }

  @Test
  void unModeleModifieChangeLeMessageSuivantSansRedeploiement() throws Exception {
    mockMvc
        .perform(
            put("/api/v1/canaux/modeles/ALERT_OPENED")
                .header("Authorization", "Bearer " + adminJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"subjectTemplate\":\"ALERTE {equipement}\","
                        + "\"bodyTemplate\":\"Panne sur {equipement} ({site}) : {message}\"}"))
        .andExpect(status().isOk());
    prefer(NotificationChannel.SLACK, "env:SLACK_URL");

    queue.queueFor(UUID.randomUUID(), alert.getId(), AlertEventType.OPENED);
    dispatch.dispatchPending();

    assertThat(RECEIVED.get(0).body())
        .contains("ALERTE Routeur des canaux")
        .contains("Panne sur Routeur des canaux (Bâtiment des canaux)");
  }

  @Test
  void uneNotificationAbandonneePeutEtreRejouee() throws Exception {
    prefer(NotificationChannel.SLACK, "env:SLACK_URL");
    queue.queueFor(UUID.randomUUID(), alert.getId(), AlertEventType.OPENED);
    em.flush();
    jdbc.update("UPDATE notification SET state = 'FAILED' WHERE alert_id = ?", alert.getId());
    em.clear();
    UUID id =
        jdbc.queryForObject(
            "SELECT id FROM notification WHERE alert_id = ?", UUID.class, alert.getId());

    mockMvc
        .perform(
            post("/api/v1/canaux/historique/" + id + "/rejouer")
                .header("Authorization", "Bearer " + adminJwt))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("PENDING"));
    mockMvc
        .perform(
            post("/api/v1/canaux/historique/" + id + "/rejouer")
                .header("Authorization", "Bearer " + adminJwt))
        .andExpect(status().isConflict());
  }
}

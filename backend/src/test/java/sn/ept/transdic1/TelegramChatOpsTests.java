package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.alerting.domain.Alert;
import sn.ept.transdic1.alerting.domain.AlertState;
import sn.ept.transdic1.alerting.domain.AlertType;
import sn.ept.transdic1.alerting.domain.Severity;
import sn.ept.transdic1.alerting.repository.AlertRepository;
import sn.ept.transdic1.chatops.service.ChatOpsService;
import sn.ept.transdic1.chatops.telegram.TelegramBot;
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
 * Prise en charge depuis Telegram (ADR 0051) : liaison par code à usage unique, puis clic sur « Je
 * prends » exécuté au nom du compte lié, avec ses droits. Le bot est remplacé par une doublure :
 * aucun appel réseau.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@TestPropertySource(properties = "chatops.telegram.enabled=true")
class TelegramChatOpsTests {
  @Autowired private MockMvc mockMvc;
  @Autowired private ChatOpsService chatOps;
  @Autowired private UserRepository users;
  @Autowired private PasswordEncoder encoder;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private AlertRepository alerts;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper json;
  @Autowired private jakarta.persistence.EntityManager em;

  @MockitoBean private TelegramBot bot;

  private final String suffix = UUID.randomUUID().toString().substring(0, 8);
  private AppUser operator;
  private AppUser viewer;
  private Alert alert;

  @BeforeEach
  void data() {
    when(bot.configured()).thenReturn(true);
    operator = account("tg-noc-" + suffix, Role.NOC_OPERATOR);
    viewer = account("tg-lecteur-" + suffix, Role.VIEWER);
    Site site = sites.saveAndFlush(new Site("TG-" + suffix, "Site Telegram"));
    Equipment server =
        equipments.saveAndFlush(
            new Equipment(
                "TG-" + UUID.randomUUID(),
                "Serveur ENT",
                "10.94." + (Math.floorMod(suffix.hashCode(), 250) + 1) + ".1",
                EquipmentType.SERVER,
                EquipmentStatus.UP,
                site));
    alert =
        alerts.saveAndFlush(
            new Alert(
                server,
                "tg:" + UUID.randomUUID(),
                AlertType.ANOMALY,
                AlertState.OPEN,
                Severity.CRITICAL,
                "Processeur saturé",
                Instant.now()));
    em.flush();
  }

  private AppUser account(String name, Role role) {
    return users.saveAndFlush(
        new AppUser(name, name + "@example.test", encoder.encode("Mot-de-passe-test-123"), role));
  }

  private String code(AppUser who) throws Exception {
    String body =
        mockMvc
            .perform(
                post("/api/v1/moi/telegram/code")
                    .with(user(who.getUsername()).roles(who.getRole().name())))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.botActive").value(true))
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(body, "$.code");
  }

  private JsonNode message(long from, String text) throws Exception {
    return json.readTree(
        "{\"update_id\":1,\"message\":{\"message_id\":10,\"text\":\""
            + text
            + "\","
            + "\"chat\":{\"id\":"
            + from
            + ",\"type\":\"private\"},"
            + "\"from\":{\"id\":"
            + from
            + ",\"username\":\"tel"
            + from
            + "\"}}}");
  }

  private JsonNode click(long from, String data) throws Exception {
    return json.readTree(
        "{\"update_id\":2,\"callback_query\":{\"id\":\"cb1\",\"data\":\""
            + data
            + "\","
            + "\"from\":{\"id\":"
            + from
            + "},"
            + "\"message\":{\"message_id\":11,\"text\":\"Alerte\",\"chat\":{\"id\":"
            + from
            + "}}}}");
  }

  private String state() {
    return jdbc.queryForObject("SELECT state FROM alert WHERE id = ?", String.class, alert.getId());
  }

  @Test
  @DisplayName("Liaison par code, puis « Je prends » depuis Telegram au nom de l'opérateur")
  void linkThenTake() throws Exception {
    String code = code(operator);
    assertThat(code).matches("[A-Z2-9]{6}");

    chatOps.handle(message(777L, "/lier " + code));
    verify(bot).sendMessage(eq(777L), contains("Compte lié : " + operator.getUsername()), any());
    mockMvc
        .perform(
            get("/api/v1/moi/telegram").with(user(operator.getUsername()).roles("NOC_OPERATOR")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.linked").value(true))
        .andExpect(jsonPath("$.telegramUsername").value("tel777"));

    chatOps.handle(click(777L, "take:" + alert.getId()));
    verify(bot).answerCallback("cb1", "Prise en charge enregistrée.");
    verify(bot)
        .editMessage(
            eq(777L),
            eq(11L),
            contains("Prise en charge par " + operator.getUsername()),
            anyString());
    assertThat(state()).isEqualTo("IN_PROGRESS");
    assertThat(
            jdbc.queryForObject(
                "SELECT comment FROM alert_event WHERE alert_id = ? AND type = 'TAKEN'",
                String.class,
                alert.getId()))
        .isEqualTo("Depuis Telegram");
    // Tracé au nom de l'opérateur, comme une action faite à l'écran.
    assertThat(
            jdbc.queryForObject(
                "SELECT username FROM audit_entry WHERE action = 'ALERT_TAKEN' AND target_id = ?",
                String.class,
                alert.getId()))
        .isEqualTo(operator.getUsername());

    // Un code ne sert qu'une fois.
    chatOps.handle(message(778L, "/lier " + code));
    verify(bot).sendMessage(eq(778L), contains("Code inconnu ou expiré"), any());
  }

  @Test
  @DisplayName("Un compte Telegram non lié ne peut rien faire")
  void unlinkedCannotAct() throws Exception {
    chatOps.handle(click(999L, "take:" + alert.getId()));
    verify(bot).answerCallback(eq("cb1"), contains("non lié"));
    assertThat(state()).isEqualTo("OPEN");
  }

  @Test
  @DisplayName("Un lecteur lié peut consulter, pas prendre en charge")
  void viewerCannotTake() throws Exception {
    chatOps.handle(message(555L, "/lier " + code(viewer)));
    chatOps.handle(click(555L, "resolve:" + alert.getId()));
    verify(bot).answerCallback(eq("cb1"), contains("lecteur"));
    assertThat(state()).isEqualTo("OPEN");
  }

  @Test
  @DisplayName("Le bot ne répond qu'en conversation privée")
  void privateChatsOnly() throws Exception {
    JsonNode group =
        json.readTree(
            "{\"update_id\":3,\"message\":{\"message_id\":12,\"text\":\"/alertes\","
                + "\"chat\":{\"id\":-100,\"type\":\"group\"},\"from\":{\"id\":777}}}");
    chatOps.handle(group);
    verify(bot).sendMessage(eq(-100L), contains("conversation privée"), any());
    verify(bot, org.mockito.Mockito.never()).answerCallback(anyString(), anyString());
    verify(bot, org.mockito.Mockito.never())
        .editMessage(anyLong(), anyLong(), anyString(), anyString());
  }
}

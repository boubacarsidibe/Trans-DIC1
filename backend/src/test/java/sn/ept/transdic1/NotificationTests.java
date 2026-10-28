package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import com.jayway.jsonpath.JsonPath;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
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
import sn.ept.transdic1.alerting.domain.AlertRule;
import sn.ept.transdic1.alerting.domain.ComparisonOperator;
import sn.ept.transdic1.alerting.domain.Maintenance;
import sn.ept.transdic1.alerting.domain.MaintenanceState;
import sn.ept.transdic1.alerting.domain.RuleEquipmentScope;
import sn.ept.transdic1.alerting.domain.Severity;
import sn.ept.transdic1.alerting.repository.AlertRuleRepository;
import sn.ept.transdic1.alerting.repository.MaintenanceRepository;
import sn.ept.transdic1.alerting.repository.RuleEquipmentScopeRepository;
import sn.ept.transdic1.collection.dto.MeasureInput;
import sn.ept.transdic1.collection.service.MeasureIngestionService;
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
import sn.ept.transdic1.security.domain.AppUser;
import sn.ept.transdic1.security.domain.Role;
import sn.ept.transdic1.security.repository.UserRepository;

/**
 * Notifications par e-mail : préférences, destinataires choisis, message réellement reçu par un
 * serveur SMTP, reprises bornées en cas de panne.
 */
class NotificationTests {
  private static final String PASSWORD = "Mot-de-passe-test-123";
  private static final GreenMail SMTP = startSmtp();

  private static GreenMail startSmtp() {
    int port = freePort();
    var setup = new ServerSetup(port, "127.0.0.1", "smtp");
    // Le démarrage peut dépasser les 2 s par défaut sur un poste chargé.
    setup.setServerStartupTimeout(20_000);
    var server = new GreenMail(setup);
    server.start();
    return server;
  }

  /** Le port est libéré avant de lancer le serveur : un socket encore ouvert l'empêcherait. */
  private static int freePort() {
    try (var socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @AfterAll
  static void stopSmtp() {
    SMTP.stop();
  }

  @DynamicPropertySource
  static void mail(DynamicPropertyRegistry registry) {
    registry.add("spring.mail.host", () -> "127.0.0.1");
    registry.add("spring.mail.port", () -> SMTP.getSmtp().getPort());
  }

  /** Données communes : un équipement, des utilisateurs aux préférences variées. */
  abstract static class Base {
    @Autowired MockMvc mockMvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired SiteRepository sites;
    @Autowired EquipmentRepository equipments;
    @Autowired AlertRuleRepository rules;
    @Autowired RuleEquipmentScopeRepository scopes;
    @Autowired NotificationPreferenceRepository preferences;
    @Autowired MeasureIngestionService ingestion;
    @Autowired NotificationDispatchService dispatch;
    @Autowired MaintenanceRepository maintenances;
    @Autowired JdbcTemplate jdbc;

    String suffix;
    Site site;
    Equipment equipment;

    @BeforeEach
    void data() throws Exception {
      SMTP.purgeEmailFromAllMailboxes();
      jdbc.update("DELETE FROM notification");
      suffix = UUID.randomUUID().toString().substring(0, 8);
      String unique = UUID.randomUUID().toString();
      site = sites.save(new Site("NTF-" + suffix, "Bâtiment des notifications"));
      equipment =
          equipments.save(
              new Equipment(
                  "NTF-EQ-" + unique,
                  "Routeur notifié",
                  "10.237."
                      + (Math.floorMod(unique.hashCode(), 250) + 1)
                      + "."
                      + (Math.floorMod(unique.substring(9).hashCode(), 250) + 1),
                  EquipmentType.ROUTER,
                  EquipmentStatus.UP,
                  site));
      equipments.flush();
    }

    AppUser user(String prefix, Role role) {
      return users.saveAndFlush(
          new AppUser(
              prefix + "-" + suffix,
              prefix + "-" + suffix + "@example.test",
              encoder.encode(PASSWORD),
              role));
    }

    void prefer(AppUser user, Severity minimum, boolean active) {
      var preference = new NotificationPreference(user, NotificationChannel.EMAIL, minimum);
      preference.setActive(active);
      preferences.saveAndFlush(preference);
    }

    AlertRule rule(Severity severity) {
      var rule =
          new AlertRule(
              "ntf-" + UUID.randomUUID(),
              "cpu.usage",
              ComparisonOperator.GREATER_THAN,
              new BigDecimal("90"),
              Duration.ZERO,
              severity);
      rule.setUnit("%");
      rule = rules.save(rule);
      scopes.save(new RuleEquipmentScope(rule, equipment));
      return rule;
    }

    void measure(String value, long minutesAgo) {
      ingestion.ingest(
          equipment.getId(),
          List.of(
              new MeasureInput(
                  "cpu.usage",
                  new BigDecimal(value),
                  "%",
                  null,
                  Instant.now().minus(minutesAgo, ChronoUnit.MINUTES))));
    }

    int queued() {
      return jdbc.queryForObject("SELECT count(*) FROM notification", Integer.class);
    }

    String login(AppUser user) throws Exception {
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

  @Nested
  @SpringBootTest
  @AutoConfigureMockMvc
  @ActiveProfiles("test")
  @Transactional
  class Delivery extends Base {

    @Test
    void anOpenedAlertIsMailedToTheRightPeopleOnly() throws Exception {
      AppUser critical = user("crit", Role.NOC_OPERATOR);
      AppUser warning = user("warn", Role.ADMIN);
      AppUser disabled = user("off", Role.NOC_OPERATOR);
      user("none", Role.NOC_OPERATOR); // aucune préférence : personne ne le dérange
      prefer(critical, Severity.CRITICAL, true);
      prefer(warning, Severity.WARNING, true);
      prefer(disabled, Severity.INFO, false);
      rule(Severity.WARNING);

      measure("95", 1);

      // Une alerte d'avertissement ne dérange pas qui ne veut que le critique.
      assertThat(queued()).isEqualTo(1);
      assertThat(dispatch.dispatchPending()).isEqualTo(1);
      MimeMessage[] mails = SMTP.getReceivedMessages();
      assertThat(mails).hasSize(1);
      assertThat(mails[0].getAllRecipients()[0].toString()).isEqualTo(warning.getEmail());
    }

    @Test
    void theMessageNamesTheEquipmentAndSiteButNeverItsAddress() throws Exception {
      prefer(user("crit", Role.NOC_OPERATOR), Severity.INFO, true);
      rule(Severity.CRITICAL);

      measure("97", 1);
      dispatch.dispatchPending();

      MimeMessage mail = SMTP.getReceivedMessages()[0];
      String body = (String) mail.getContent();
      assertThat(mail.getSubject()).isEqualTo("[CRITIQUE] Alerte ouverte — Routeur notifié");
      assertThat(mail.getFrom()[0].toString()).isEqualTo("trans-dic1@ept.sn");
      assertThat(body)
          .contains("Routeur notifié")
          .contains("Bâtiment des notifications")
          .contains("cpu.usage = 97 %")
          .contains("/alertes")
          .doesNotContain("10.237.");
    }

    @Test
    void aResolvedAlertSendsAResolutionMessage() throws Exception {
      prefer(user("crit", Role.NOC_OPERATOR), Severity.INFO, true);
      rule(Severity.CRITICAL);
      measure("97", 2);
      dispatch.dispatchPending();

      measure("40", 1);
      dispatch.dispatchPending();

      MimeMessage[] mails = SMTP.getReceivedMessages();
      assertThat(mails).hasSize(2);
      assertThat(mails[1].getSubject()).isEqualTo("[CRITIQUE] Alerte résolue — Routeur notifié");
      assertThat((String) mails[1].getContent()).contains("Alerte résolue").contains("Résolue le");
    }

    @Test
    void sentNotificationsAreTracedAndNotSentTwice() throws Exception {
      prefer(user("crit", Role.NOC_OPERATOR), Severity.INFO, true);
      rule(Severity.CRITICAL);
      measure("97", 1);

      assertThat(dispatch.dispatchPending()).isEqualTo(1);
      assertThat(dispatch.dispatchPending()).isZero();

      assertThat(
              jdbc.queryForObject(
                  "SELECT state FROM notification WHERE alert_id IS NOT NULL", String.class))
          .isEqualTo("SENT");
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM notification_attempt WHERE result = 'SUCCESS'",
                  Integer.class))
          .isEqualTo(1);
      assertThat(SMTP.getReceivedMessages()).hasSize(1);
    }

    @Test
    void duringAMaintenanceNobodyIsNotified() throws Exception {
      AppUser planner = user("plan", Role.ADMIN);
      prefer(user("crit", Role.NOC_OPERATOR), Severity.INFO, true);
      rule(Severity.CRITICAL);
      var maintenance =
          new Maintenance(
              planner,
              Instant.now().minus(1, ChronoUnit.HOURS),
              Instant.now().plus(1, ChronoUnit.HOURS),
              "Mise à jour",
              MaintenanceState.IN_PROGRESS);
      maintenance.setEquipment(equipment);
      maintenances.saveAndFlush(maintenance);

      measure("97", 1);

      assertThat(queued()).isZero();
      assertThat(dispatch.dispatchPending()).isZero();
      assertThat(SMTP.getReceivedMessages()).isEmpty();
    }

    @Test
    void usersEditTheirOwnPreferencesThroughTheApi() throws Exception {
      AppUser viewer = user("vwr", Role.VIEWER);
      String jwt = login(viewer);

      mockMvc
          .perform(get("/api/v1/mes-preferences").header("Authorization", "Bearer " + jwt))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.active").value(false))
          .andExpect(jsonPath("$.minimumSeverity").value("CRITICAL"))
          .andExpect(jsonPath("$.email").value(viewer.getEmail()));

      mockMvc
          .perform(
              put("/api/v1/mes-preferences")
                  .header("Authorization", "Bearer " + jwt)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      "{\"channel\":\"EMAIL\",\"minimumSeverity\":\"WARNING\",\"active\":true}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.active").value(true))
          .andExpect(jsonPath("$.minimumSeverity").value("WARNING"));
      mockMvc
          .perform(get("/api/v1/mes-preferences").header("Authorization", "Bearer " + jwt))
          .andExpect(jsonPath("$.minimumSeverity").value("WARNING"));
      mockMvc
          .perform(
              put("/api/v1/mes-preferences")
                  .header("Authorization", "Bearer " + jwt)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"channel\":\"SMS\",\"minimumSeverity\":\"WARNING\",\"active\":true}"))
          .andExpect(status().isBadRequest());
      mockMvc.perform(get("/api/v1/mes-preferences")).andExpect(status().isUnauthorized());
    }
  }

  @Nested
  @SpringBootTest(properties = "notification.max-attempts=3")
  @AutoConfigureMockMvc
  @ActiveProfiles("test")
  @Transactional
  class SmtpDown extends Base {

    @org.junit.jupiter.api.BeforeEach
    void smtpGoesDown() {
      SMTP.stop();
    }

    @org.junit.jupiter.api.AfterEach
    void smtpComesBack() {
      SMTP.start();
    }

    @Test
    void failedSendsAreRetriedThenAbandonedWithoutLeakingTheServer() {
      prefer(user("crit", Role.NOC_OPERATOR), Severity.INFO, true);
      rule(Severity.CRITICAL);
      measure("97", 1);

      for (int pass = 1; pass <= 5; pass++) {
        assertThat(dispatch.dispatchPending()).isZero();
      }

      assertThat(
              jdbc.queryForObject(
                  "SELECT state FROM notification WHERE alert_id IS NOT NULL", String.class))
          .isEqualTo("FAILED");
      var attempts =
          jdbc.queryForList(
              "SELECT result FROM notification_attempt ORDER BY number", String.class);
      assertThat(attempts).containsExactly("FAILURE", "FAILURE", "FAILURE");
      var errors =
          jdbc.queryForList(
              "SELECT sanitized_error FROM notification_attempt WHERE sanitized_error IS NOT NULL",
              String.class);
      assertThat(errors).isNotEmpty().allSatisfy(e -> assertThat(e).doesNotContain("127.0.0.1"));
    }
  }
}

package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import jakarta.mail.Multipart;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.repository.SiteRepository;
import sn.ept.transdic1.reporting.domain.ReportFrequency;
import sn.ept.transdic1.reporting.repository.ReportScheduleRepository;
import sn.ept.transdic1.reporting.service.ReportScheduleAdminService;
import sn.ept.transdic1.reporting.service.ReportScheduleAdminService.ScheduleRequest;
import sn.ept.transdic1.reporting.service.ScheduledReportService;
import sn.ept.transdic1.security.domain.AppUser;
import sn.ept.transdic1.security.domain.Role;
import sn.ept.transdic1.security.repository.UserRepository;

/**
 * Rapports planifiés : période couverte, prochaine échéance, et envoi réel d'un e-mail avec le PDF
 * en pièce jointe à un serveur SMTP de test.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ScheduledReportTests {
  private static final GreenMail SMTP = startSmtp();

  private static GreenMail startSmtp() {
    int port = freePort();
    var setup = new ServerSetup(port, "127.0.0.1", "smtp");
    setup.setServerStartupTimeout(20_000);
    var server = new GreenMail(setup);
    server.start();
    return server;
  }

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

  @Autowired private ReportScheduleAdminService admin;
  @Autowired private ScheduledReportService sender;
  @Autowired private ReportScheduleRepository schedules;
  @Autowired private UserRepository users;
  @Autowired private SiteRepository sites;
  @Autowired private PasswordEncoder encoder;
  @Autowired private jakarta.persistence.EntityManager em;

  private AppUser admin1;
  private AppUser reader;

  @BeforeEach
  void data() throws Exception {
    SMTP.purgeEmailFromAllMailboxes();
    admin1 = account("rap-admin", Role.ADMIN);
    reader = account("rap-lecteur", Role.VIEWER);
    sites.saveAndFlush(
        new Site("RAP-" + UUID.randomUUID().toString().substring(0, 8), "Site des rapports"));
  }

  private AppUser account(String prefix, Role role) {
    String unique = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    return users.saveAndFlush(
        new AppUser(unique, unique + "@ept.test", encoder.encode("MotDePasse-123456"), role));
  }

  private ScheduleRequest request(ReportFrequency frequency, String time, List<UUID> people) {
    return new ScheduleRequest("Bilan du parc", frequency, time, true, people, List.of());
  }

  @Test
  void lEnvoiPartAvecLeRapportEnPieceJointe() throws Exception {
    var created =
        admin.create(
            request(ReportFrequency.WEEKLY, "07:30", List.of(reader.getId())),
            admin1.getUsername());
    em.flush();

    assertThat(admin.sendNow(created.id(), admin1.getUsername())).isTrue();

    assertThat(SMTP.waitForIncomingEmail(10_000, 1)).isTrue();
    MimeMessage[] received = SMTP.getReceivedMessages();
    assertThat(received).hasSize(1);
    assertThat(received[0].getSubject()).startsWith("Trans-DIC1 — Bilan du parc —");
    assertThat(received[0].getAllRecipients()[0].toString()).isEqualTo(reader.getEmail());

    // Le PDF est bien joint, et nommé par la période.
    Multipart parts = (Multipart) received[0].getContent();
    boolean pdf = false;
    for (int i = 0; i < parts.getCount(); i++) {
      String name = parts.getBodyPart(i).getFileName();
      if (name != null && name.endsWith(".pdf")) {
        pdf = true;
        assertThat(name).startsWith("trans-dic1-disponibilite-");
        assertThat(parts.getBodyPart(i).getSize()).isPositive();
      }
    }
    assertThat(pdf).isTrue();
  }

  @Test
  void unePlanificationSansDestinataireEstRefusee() {
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    admin.create(
                        request(ReportFrequency.DAILY, "08:00", List.of()), admin1.getUsername())))
        .hasMessageContaining("au moins un destinataire");
  }

  @Test
  void uneHeureInvalideEstRefusee() {
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    admin.create(
                        request(ReportFrequency.DAILY, "25h", List.of(reader.getId())),
                        admin1.getUsername())))
        .hasMessageContaining("HH:MM");
  }

  @Test
  void laPeriodeCouverteEstCelleQuiVientDeSAchever() {
    ZoneId dakar = ZoneId.of("Africa/Dakar");
    Instant now = ZonedDateTime.of(2026, 11, 11, 7, 30, 0, 0, dakar).toInstant();

    var daily = sender.period(ReportFrequency.DAILY, now);
    assertThat(daily.fromDate(dakar)).isEqualTo("2026-11-10");
    assertThat(daily.toDate(dakar)).isEqualTo("2026-11-10");

    var weekly = sender.period(ReportFrequency.WEEKLY, now);
    assertThat(weekly.fromDate(dakar)).isEqualTo("2026-11-04");
    assertThat(weekly.toDate(dakar)).isEqualTo("2026-11-10");

    var monthly = sender.period(ReportFrequency.MONTHLY, now);
    assertThat(monthly.fromDate(dakar)).isEqualTo("2026-10-11");
  }

  @Test
  void laProchaineEcheanceEstLaProchaineOccurrenceDeLHeureDite() {
    var created =
        admin.create(
            request(ReportFrequency.DAILY, "07:30", List.of(reader.getId())), admin1.getUsername());
    var schedule = schedules.findById(created.id()).orElseThrow();

    ZoneId dakar = ZoneId.of("Africa/Dakar");
    Instant morning = ZonedDateTime.of(2026, 11, 11, 6, 0, 0, 0, dakar).toInstant();
    assertThat(sender.nextRun(schedule, morning))
        .isEqualTo(ZonedDateTime.of(2026, 11, 11, 7, 30, 0, 0, dakar).toInstant());

    // Passée l'heure, c'est le lendemain : jamais une rafale de rattrapage.
    Instant evening = ZonedDateTime.of(2026, 11, 11, 20, 0, 0, 0, dakar).toInstant();
    assertThat(sender.nextRun(schedule, evening))
        .isEqualTo(ZonedDateTime.of(2026, 11, 12, 7, 30, 0, 0, dakar).toInstant());
    assertThat(schedule.getExecutionTime()).isEqualTo(LocalTime.of(7, 30));
  }

  @Test
  void unDestinataireDesactiveNeRecoitRien() throws Exception {
    reader.setActive(false);
    users.saveAndFlush(reader);
    var created =
        admin.create(
            request(ReportFrequency.DAILY, "07:30", List.of(reader.getId())), admin1.getUsername());
    em.flush();

    assertThat(admin.sendNow(created.id(), admin1.getUsername())).isFalse();
    assertThat(SMTP.getReceivedMessages()).isEmpty();
  }
}

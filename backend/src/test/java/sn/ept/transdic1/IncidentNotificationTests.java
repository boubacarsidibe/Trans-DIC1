package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.alerting.domain.Alert;
import sn.ept.transdic1.alerting.domain.AlertChangedEvent;
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
import sn.ept.transdic1.security.domain.AppUser;
import sn.ept.transdic1.security.domain.Role;
import sn.ept.transdic1.security.repository.UserRepository;

/**
 * Un fait, une notification (M10, ADR 0039), éprouvé de bout en bout : l'événement d'ouverture est
 * publié comme un détecteur le fait, et l'on compte ce qui est réellement mis en file.
 *
 * <p>C'est le test qui compte, parce que la suppression repose sur un <b>ordre d'écouteurs</b> : le
 * regroupement doit traiter l'événement avant la file des notifications, sans quoi le message est
 * déjà écrit quand l'incident apprend qu'il existe.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class IncidentNotificationTests {
  @Autowired private ApplicationEventPublisher publisher;
  @Autowired private AlertRepository alerts;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private UserRepository users;
  @Autowired private NotificationPreferenceRepository preferences;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private jakarta.persistence.EntityManager em;

  private Equipment router;
  private AppUser operator;

  @BeforeEach
  void data() {
    String unique = UUID.randomUUID().toString().substring(0, 8);
    Site site = sites.saveAndFlush(new Site("NOT-" + unique, "Site notifications"));
    router =
        equipments.saveAndFlush(
            new Equipment(
                "NOT-EQ-" + UUID.randomUUID(),
                "Routeur notifié",
                "10.98." + (Math.floorMod(unique.hashCode(), 250) + 1) + ".1",
                EquipmentType.ROUTER,
                EquipmentStatus.UP,
                site));
    operator =
        users.saveAndFlush(
            new AppUser(
                "notif-" + unique, "notif-" + unique + "@example.test", "x", Role.NOC_OPERATOR));
    var preference = new NotificationPreference(operator, NotificationChannel.EMAIL, Severity.INFO);
    preferences.saveAndFlush(preference);
    em.flush();
  }

  /** Ouvre une alerte et publie son événement, comme un détecteur. */
  private Alert open(Severity severity, String message) {
    var alert =
        new Alert(
            router,
            "notif:" + UUID.randomUUID(),
            AlertType.ANOMALY,
            AlertState.OPEN,
            severity,
            message,
            Instant.now());
    alerts.saveAndFlush(alert);
    publisher.publishEvent(new AlertChangedEvent(alert.getId(), AlertEventType.OPENED));
    em.flush();
    return alert;
  }

  private int messages() {
    em.flush();
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM notification WHERE user_id = ?", Integer.class, operator.getId());
    return count == null ? 0 : count;
  }

  @Test
  @DisplayName("Trois alertes sur la même machine : un seul message")
  void threeAlertsOnTheSameMachineOnemessage() {
    open(Severity.WARNING, "Processeur inhabituel");
    open(Severity.WARNING, "Mémoire inhabituelle");
    open(Severity.WARNING, "Latence inhabituelle");

    // Avant M10, l'opérateur recevait trois messages pour un seul fait.
    assertThat(messages()).isEqualTo(1);
  }

  @Test
  @DisplayName("Un incident qui s'aggrave envoie un second message, et un seul")
  void anincidentThatWorsensSendsAsecondMessageAndOnlyOne() {
    open(Severity.WARNING, "Processeur inhabituel");
    open(Severity.CRITICAL, "Perte de collecte imminente");
    open(Severity.CRITICAL, "Mémoire inhabituelle");

    // Le passage en critique doit réveiller quelqu'un ; la seconde alerte critique n'apprend rien.
    assertThat(messages()).isEqualTo(2);
  }

  @Test
  @DisplayName("Les alertes rangées sont bien rangées, pas perdues")
  void thegroupedAlertsAreFiledNotLost() {
    open(Severity.WARNING, "Processeur inhabituel");
    open(Severity.WARNING, "Mémoire inhabituelle");
    em.flush();

    Integer incidents =
        jdbc.queryForObject(
            "SELECT count(DISTINCT incident_id) FROM incident_alert", Integer.class);
    Integer filed =
        jdbc.queryForObject(
            "SELECT count(*) FROM incident_alert ia JOIN alert a ON a.id = ia.alert_id"
                + " WHERE a.equipment_id = ?",
            Integer.class,
            router.getId());

    assertThat(incidents).isPositive();
    assertThat(filed).isEqualTo(2);
  }

  @Test
  @DisplayName("Une alerte qui n'appartient à aucun incident notifie toujours")
  void analertInNoincidentStillNotifies() {
    // Le regroupement ne doit jamais faire taire ce qu'il n'a pas rangé : une alerte créée sans
    // passer par lui garde sa notification.
    var alert =
        new Alert(
            router,
            "notif:" + UUID.randomUUID(),
            AlertType.ANOMALY,
            AlertState.OPEN,
            Severity.WARNING,
            "Processeur inhabituel",
            Instant.now());
    alerts.saveAndFlush(alert);
    jdbc.update("DELETE FROM incident_alert WHERE alert_id = ?", alert.getId());
    em.flush();

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM incident_alert WHERE alert_id = ?",
                Integer.class,
                alert.getId()))
        .isZero();
  }
}

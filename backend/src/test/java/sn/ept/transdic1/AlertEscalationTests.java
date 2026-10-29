package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.alerting.domain.Alert;
import sn.ept.transdic1.alerting.domain.AlertEventType;
import sn.ept.transdic1.alerting.domain.AlertState;
import sn.ept.transdic1.alerting.domain.AlertType;
import sn.ept.transdic1.alerting.domain.Severity;
import sn.ept.transdic1.alerting.repository.AlertEventRepository;
import sn.ept.transdic1.alerting.repository.AlertRepository;
import sn.ept.transdic1.alerting.service.AlertEscalationService;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.EquipmentStatus;
import sn.ept.transdic1.inventory.domain.EquipmentType;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.SiteRepository;
import sn.ept.transdic1.security.domain.AppUser;
import sn.ept.transdic1.security.domain.Role;
import sn.ept.transdic1.security.repository.UserRepository;

/** Escalade : une alerte non prise en charge prévient les administrateurs, une seule fois. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AlertEscalationTests {
  @Autowired private AlertEscalationService escalation;
  @Autowired private AlertRepository alerts;
  @Autowired private AlertEventRepository events;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private UserRepository users;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private jakarta.persistence.EntityManager em;

  private Site site;
  private String adminEmail;

  @BeforeEach
  void data() {
    String unique = UUID.randomUUID().toString();
    site = sites.save(new Site("ESC-" + unique.substring(0, 8), "Site d'escalade"));
    String suffix = unique.substring(0, 8);
    adminEmail = "esc-" + suffix + "@example.test";
    users.save(new AppUser("esc-" + suffix, adminEmail, "x", Role.ADMIN));
  }

  private Alert alert(Severity severity, AlertState state, long minutesAgo) {
    // Une seule alerte de collecte active par équipement : un équipement par alerte.
    String unique = UUID.randomUUID().toString();
    Equipment equipment =
        equipments.save(
            new Equipment(
                "ESC-EQ-" + unique,
                "Routeur oublié",
                "10.238."
                    + (Math.floorMod(unique.hashCode(), 250) + 1)
                    + "."
                    + (Math.floorMod(unique.substring(9).hashCode(), 250) + 1),
                EquipmentType.ROUTER,
                EquipmentStatus.UP,
                site));
    return alerts.save(
        new Alert(
            equipment,
            "esc-" + UUID.randomUUID(),
            AlertType.COLLECTION,
            state,
            severity,
            "Test d'escalade",
            Instant.now().minus(minutesAgo, ChronoUnit.MINUTES)));
  }

  private int notificationsFor(Alert alert) {
    em.flush();
    return jdbc.queryForObject(
        "SELECT count(*) FROM notification n JOIN app_user u ON u.id = n.user_id"
            + " WHERE n.alert_id = ? AND u.email = ?",
        Integer.class,
        alert.getId(),
        adminEmail);
  }

  @Test
  void uneAlerteOubliee_estEscaladeUneSeuleFoisEtLesAdministrateursSontPrevenus() {
    Alert old = alert(Severity.WARNING, AlertState.OPEN, 45);

    escalation.escalateOverdue();
    em.flush();

    assertThat(alerts.findById(old.getId()).orElseThrow().getEscalatedAt()).isNotNull();
    assertThat(events.findByAlertIdOrderByOccurredAtAsc(old.getId()))
        .extracting(e -> e.getType())
        .contains(AlertEventType.ESCALATED);
    assertThat(notificationsFor(old)).isEqualTo(1);

    // Un second passage ne prévient plus personne.
    escalation.escalateOverdue();
    assertThat(notificationsFor(old)).isEqualTo(1);
    assertThat(
            events.findByAlertIdOrderByOccurredAtAsc(old.getId()).stream()
                .filter(e -> e.getType() == AlertEventType.ESCALATED)
                .count())
        .isEqualTo(1);
  }

  @Test
  void leDelaiEstPlusCourtPourUneAlerteCritique() {
    Alert critical = alert(Severity.CRITICAL, AlertState.OPEN, 15);
    Alert warning = alert(Severity.WARNING, AlertState.OPEN, 15);

    escalation.escalateOverdue();

    assertThat(alerts.findById(critical.getId()).orElseThrow().getEscalatedAt()).isNotNull();
    assertThat(alerts.findById(warning.getId()).orElseThrow().getEscalatedAt()).isNull();
  }

  @Test
  void uneAlertePriseEnChargeOuRecenteNEstPasEscaladee() {
    Alert taken = alert(Severity.CRITICAL, AlertState.IN_PROGRESS, 120);
    Alert fresh = alert(Severity.CRITICAL, AlertState.OPEN, 2);

    escalation.escalateOverdue();

    for (Alert a : new Alert[] {taken, fresh}) {
      assertThat(alerts.findById(a.getId()).orElseThrow().getEscalatedAt()).isNull();
    }
  }
}

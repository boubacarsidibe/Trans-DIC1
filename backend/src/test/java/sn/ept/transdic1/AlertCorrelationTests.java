package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.alerting.domain.Alert;
import sn.ept.transdic1.alerting.domain.AlertType;
import sn.ept.transdic1.alerting.domain.Severity;
import sn.ept.transdic1.alerting.repository.AlertRepository;
import sn.ept.transdic1.alerting.service.AlertCorrelationService;
import sn.ept.transdic1.alerting.service.CollectionAlertService;
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

/** Regroupement : topologie, site et fenêtre de temps, explication, désactivation, bruit réduit. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AlertCorrelationTests {
  private static final Instant T0 = Instant.parse("2026-10-31T08:00:00Z");

  @Autowired private CollectionAlertService collection;
  @Autowired private AlertCorrelationService correlation;
  @Autowired private AlertRepository alerts;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private UserRepository users;
  @Autowired private NotificationPreferenceRepository preferences;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private jakarta.persistence.EntityManager em;

  private Site site;

  @BeforeEach
  void data() {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    site = sites.save(new Site("COR-" + suffix, "Site corrélation"));
    // Un destinataire qui veut tout recevoir : on compte les notifications réellement préparées.
    var user =
        users.save(
            new AppUser(
                "cor-" + suffix, "cor-" + suffix + "@example.test", "x", Role.NOC_OPERATOR));
    preferences.save(new NotificationPreference(user, NotificationChannel.EMAIL, Severity.INFO));
  }

  @AfterEach
  void enable() {
    correlation.setEnabled(true);
  }

  private Equipment equipment(String name, Site where) {
    String unique = UUID.randomUUID().toString();
    var created =
        equipments.save(
            new Equipment(
                "COR-EQ-" + unique,
                name,
                "10.231."
                    + (Math.floorMod(unique.hashCode(), 250) + 1)
                    + "."
                    + (Math.floorMod(unique.substring(9).hashCode(), 250) + 1),
                EquipmentType.SWITCH,
                EquipmentStatus.UP,
                where));
    equipments.flush();
    return created;
  }

  private void link(Equipment child, Equipment upstream) {
    jdbc.update(
        "INSERT INTO equipment_link(id, equipment_id, upstream_id) VALUES (?, ?, ?)",
        UUID.randomUUID(),
        child.getId(),
        upstream.getId());
  }

  private Alert fail(Equipment equipment, Instant at) {
    collection.reportFailure(equipment, "Plus de réponse", at);
    em.flush();
    return alerts
        .findFirstByEquipmentIdAndTypeAndStateIn(
            equipment.getId(),
            AlertType.COLLECTION,
            java.util.Set.of(sn.ept.transdic1.alerting.domain.AlertState.OPEN))
        .orElseThrow();
  }

  private int notifications(Alert alert) {
    em.flush();
    return jdbc.queryForObject(
        "SELECT count(*) FROM notification WHERE alert_id = ?", Integer.class, alert.getId());
  }

  @Test
  void lesPannesEnAvalSontRattacheesALaRacineEtNeNotifientPas() {
    Equipment core = equipment("Routeur cœur", site);
    Equipment sw = equipment("Commutateur", site);
    Equipment server = equipment("Serveur", site);
    link(sw, core);
    link(server, sw);

    Alert root = fail(core, T0);
    Alert child = fail(sw, T0.plusSeconds(20));
    Alert grandChild = fail(server, T0.plusSeconds(40));

    assertThat(root.getParentAlertId()).isNull();
    assertThat(child.getParentAlertId()).isEqualTo(root.getId());
    assertThat(child.getCorrelationReason()).isEqualTo("Amont injoignable : Routeur cœur");
    // La racine suspectée est l'amont en panne le plus éloigné, pas le commutateur intermédiaire.
    assertThat(grandChild.getParentAlertId()).isEqualTo(root.getId());
    // Mesure du bruit : trois équipements tombés, une seule notification.
    assertThat(notifications(root)).isEqualTo(1);
    assertThat(notifications(child) + notifications(grandChild)).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM alert_event WHERE alert_id = ? AND type = 'CORRELATED'",
                Integer.class,
                child.getId()))
        .isEqualTo(1);
  }

  @Test
  void uneRacineVueApresSesDependantsLesRattacheAPosteriori() {
    Equipment core = equipment("Routeur cœur", site);
    Equipment sw =
        equipment(
            "Commutateur",
            sites.save(
                new Site("COR-B-" + UUID.randomUUID().toString().substring(0, 6), "Autre site")));
    link(sw, core);

    Alert child = fail(sw, T0);
    Alert root = fail(core, T0.plusSeconds(30));
    em.refresh(child);

    assertThat(child.getParentAlertId()).isEqualTo(root.getId());
    assertThat(child.getCorrelationReason()).contains("Routeur cœur");
  }

  @Test
  void sansTopologieLeSiteEtLaFenetreDeTempsRegroupent() {
    Equipment a = equipment("Borne A", site);
    Equipment b = equipment("Borne B", site);
    Equipment c = equipment("Borne C", site);

    Alert first = fail(a, Instant.now().minusSeconds(60));
    Alert second = fail(b, Instant.now());
    assertThat(second.getParentAlertId()).isEqualTo(first.getId());
    assertThat(second.getCorrelationReason()).startsWith("Même site : Borne A");

    // Hors de la fenêtre : une panne indépendante.
    jdbc.update(
        "UPDATE alert SET triggered_at = ? WHERE id = ?",
        java.sql.Timestamp.from(Instant.now().minus(Duration.ofHours(1))),
        first.getId());
    em.clear();
    Alert third = fail(c, Instant.now());
    assertThat(third.getParentAlertId()).isNull();
  }

  @Test
  void leRegroupementPeutEtreDesactive() {
    correlation.setEnabled(false);
    Equipment core = equipment("Routeur cœur", site);
    Equipment sw = equipment("Commutateur", site);
    link(sw, core);

    Alert root = fail(core, T0);
    Alert child = fail(sw, T0.plusSeconds(10));

    assertThat(child.getParentAlertId()).isNull();
    assertThat(notifications(root)).isEqualTo(1);
    assertThat(notifications(child)).isEqualTo(1);
  }
}

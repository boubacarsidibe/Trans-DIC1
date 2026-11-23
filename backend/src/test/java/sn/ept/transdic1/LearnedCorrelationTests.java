package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.alerting.domain.AlertState;
import sn.ept.transdic1.alerting.domain.AlertType;
import sn.ept.transdic1.alerting.service.IncidentGroupingService;
import sn.ept.transdic1.alerting.service.LearnedCorrelationService;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.EquipmentStatus;
import sn.ept.transdic1.inventory.domain.EquipmentType;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.SiteRepository;

/**
 * Corrélation apprise (M9) sur une vraie base : ce que la fouille propose, ce qu'elle refuse de
 * proposer, et ce qu'une proposition acceptée change au regroupement en incidents.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class LearnedCorrelationTests {
  @Autowired private LearnedCorrelationService learned;
  @Autowired private IncidentGroupingService grouping;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private jakarta.persistence.EntityManager em;

  private Site site;
  private Equipment server;
  private Equipment storage;
  private Equipment lonely;
  private Instant base;

  @BeforeEach
  void data() {
    // Les alertes des autres classes fausseraient les comptes de fenêtres.
    jdbc.update("DELETE FROM incident_alert");
    jdbc.update("DELETE FROM incident");
    jdbc.update("DELETE FROM learned_correlation");
    jdbc.update("DELETE FROM alert_event");
    jdbc.update("DELETE FROM notification WHERE alert_id IS NOT NULL");
    jdbc.update("DELETE FROM alert");
    String unique = UUID.randomUUID().toString().substring(0, 8);
    site = sites.saveAndFlush(new Site("APP-" + unique, "Site apprentissage"));
    server = equipment("Serveur applicatif", 1);
    storage = equipment("Baie de stockage", 2);
    lonely = equipment("Borne isolée", 3);
    em.flush();
    base =
        Instant.now().minus(Duration.ofDays(10)).truncatedTo(java.time.temporal.ChronoUnit.HOURS);
  }

  /**
   * La fouille écrit par JPA et {@code proposed()} relit en SQL : en production la transaction de
   * la tâche planifiée est validée avant qu'un écran ne lise, ici il faut vider la session.
   */
  private int mine() {
    int created = learned.mine();
    em.flush();
    return created;
  }

  private Equipment equipment(String name, int host) {
    return equipments.saveAndFlush(
        new Equipment(
            "APP-EQ-" + UUID.randomUUID(),
            name,
            "10.91." + (Math.floorMod(name.hashCode(), 250) + 1) + "." + host,
            EquipmentType.SERVER,
            EquipmentStatus.UP,
            site));
  }

  /** Une alerte déjà ouverte à un instant donné, comme l'historique en contient. */
  private void past(Equipment on, Instant at) {
    jdbc.update(
        "INSERT INTO alert(id, equipment_id, occurrence_key, type, state, severity, message,"
            + " triggered_at, resolved_at, resolution_type) VALUES (?, ?, ?, 'ANOMALY',"
            + " 'RESOLVED', 'WARNING', 'Historique', ?, ?, 'AUTOMATIC')",
        UUID.randomUUID(),
        on.getId(),
        "hist:" + UUID.randomUUID(),
        java.sql.Timestamp.from(at),
        java.sql.Timestamp.from(at.plusSeconds(600)));
  }

  /** Six occasions où les deux tombent dans la même fenêtre de dix minutes. */
  private void sixTimesTogether() {
    for (int day = 0; day < 6; day++) {
      Instant at = base.plus(Duration.ofDays(day));
      past(server, at);
      past(storage, at.plusSeconds(120));
    }
    em.flush();
  }

  @Test
  @DisplayName("Deux équipements qui tombent ensemble six fois sont proposés")
  void twoequipmentsFallingTogetherSixTimesAreProposed() {
    sixTimesTogether();

    int created = mine();

    assertThat(created).isEqualTo(1);
    var proposals = learned.proposed();
    assertThat(proposals).hasSize(1);
    var proposal = proposals.get(0);
    assertThat(List.of(proposal.firstEquipmentName(), proposal.secondEquipmentName()))
        .containsExactlyInAnyOrder("Serveur applicatif", "Baie de stockage");
    assertThat(proposal.together()).isEqualTo(6);
    assertThat(proposal.confidence()).isEqualTo(1.0);
  }

  @Test
  @DisplayName("Un équipement qui tombe seul n'est lié à personne")
  void anequipmentFallingAloneIsLinkedToNobody() {
    sixTimesTogether();
    for (int day = 0; day < 6; day++) {
      past(lonely, base.plus(Duration.ofDays(day)).plusSeconds(3_600));
    }
    em.flush();

    mine();

    assertThat(learned.proposed())
        .noneSatisfy(
            proposal ->
                assertThat(List.of(proposal.firstEquipmentId(), proposal.secondEquipmentId()))
                    .contains(lonely.getId()));
  }

  @Test
  @DisplayName("Ce que la topologie sait déjà n'est pas proposé")
  void whatThetopologyAlreadyKnowsIsNotProposed() {
    jdbc.update(
        "INSERT INTO equipment_link(id, equipment_id, upstream_id) VALUES (?, ?, ?)",
        UUID.randomUUID(),
        storage.getId(),
        server.getId());
    sixTimesTogether();

    assertThat(mine()).isZero();
    assertThat(learned.proposed()).isEmpty();
  }

  @Test
  @DisplayName("Une proposition écartée ne revient pas au passage suivant")
  void arejectedProposalDoesNotComeBack() {
    sixTimesTogether();
    mine();
    UUID id = learned.proposed().get(0).id();

    learned.reject(id, "admin-test");
    em.flush();
    int again = mine();

    assertThat(again).isZero();
    assertThat(learned.proposed()).isEmpty();
  }

  @Test
  @DisplayName("Une proposition déjà tranchée ne peut pas l'être deux fois")
  void analreadyDecidedProposalCannotBeDecidedTwice() {
    sixTimesTogether();
    mine();
    UUID id = learned.proposed().get(0).id();
    learned.accept(id, "admin-test");
    em.flush();

    assertThatThrownBy(() -> learned.accept(id, "admin-test"))
        .hasMessageContaining("déjà été tranchée");
    assertThatThrownBy(() -> learned.reject(id, "admin-test"))
        .hasMessageContaining("déjà été tranchée");
  }

  @Test
  @DisplayName("Accepter et écarter sont tracés dans le journal d'audit")
  void acceptingAndRejectingAretracedInTheAuditLog() {
    sixTimesTogether();
    mine();
    learned.accept(learned.proposed().get(0).id(), "admin-test");
    em.flush();

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_entry WHERE action = 'LEARNED_CORRELATION_ACCEPTED'",
                Integer.class))
        .isPositive();
  }

  @Test
  @DisplayName("Sans acceptation, deux alertes que rien ne relie restent deux incidents")
  void withoutAcceptanceTwoUnlinkedAlertsStayTwoincidents() {
    sixTimesTogether();
    mine();

    // Deux messages sans vocabulaire commun, aucun lien de topologie : le regroupement de l'ADR
    // 0039 n'a aucune raison de les rapprocher, et la proposition n'est pas acceptée.
    var one = grouping.attach(openAlert(server, "Processeur inhabituel"), Instant.now(), true);
    var two = grouping.attach(openAlert(storage, "Disque presque plein"), Instant.now(), true);

    assertThat(two.orElseThrow().incidentId()).isNotEqualTo(one.orElseThrow().incidentId());
  }

  @Test
  @DisplayName("Ce que M9 apporte : un lien accepté regroupe des alertes que rien ne reliait")
  void whatM9addsAnacceptedLinkGroupsAlertsNothingLinked() {
    sixTimesTogether();
    mine();
    learned.accept(learned.proposed().get(0).id(), "admin-test");
    em.flush();

    var one = grouping.attach(openAlert(server, "Processeur inhabituel"), Instant.now(), true);
    var two = grouping.attach(openAlert(storage, "Disque presque plein"), Instant.now(), true);

    assertThat(two.orElseThrow().incidentId()).isEqualTo(one.orElseThrow().incidentId());
    // Le message distingue un lien appris d'un câble : l'opérateur doit savoir ce qu'il regarde.
    assertThat(two.orElseThrow().reason())
        .isEqualTo("Lien appris des alertes, accepté : Serveur applicatif");
  }

  private UUID openAlert(Equipment on, String message) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO alert(id, equipment_id, occurrence_key, type, state, severity, message,"
            + " triggered_at) VALUES (?, ?, ?, ?, 'OPEN', 'WARNING', ?, now())",
        id,
        on.getId(),
        "ouvert:" + UUID.randomUUID(),
        AlertType.ANOMALY.name(),
        message);
    em.flush();
    return id;
  }

  @Test
  @DisplayName("Sans historique, la fouille ne propose rien et ne casse pas")
  void withoutHistoryTheMiningProposesNothing() {
    assertThat(mine()).isZero();
    assertThat(learned.proposed()).isEmpty();
  }

  @Test
  @DisplayName("Les alertes supprimées ne comptent pas dans l'historique")
  void deletedAlertsDoNotCountInTheHistory() {
    sixTimesTogether();
    jdbc.update("UPDATE alert SET state = 'DELETED' WHERE equipment_id = ?", storage.getId());
    em.flush();

    assertThat(mine()).isZero();
  }

  @Test
  @DisplayName("L'état de l'alerte ouverte n'empêche pas de compter son ouverture")
  void thestateOfanopenAlertDoesNotPreventCountingItsOpening() {
    // Une alerte encore ouverte fait partie de l'historique : c'est son ouverture qui compte.
    for (int day = 0; day < 6; day++) {
      Instant at = base.plus(Duration.ofDays(day));
      past(server, at);
      jdbc.update(
          "INSERT INTO alert(id, equipment_id, occurrence_key, type, state, severity, message,"
              + " triggered_at) VALUES (?, ?, ?, 'ANOMALY', ?, 'WARNING', 'Encore ouverte', ?)",
          UUID.randomUUID(),
          storage.getId(),
          "ouv:" + UUID.randomUUID(),
          AlertState.OPEN.name(),
          java.sql.Timestamp.from(at.plusSeconds(60)));
    }
    em.flush();

    assertThat(mine()).isEqualTo(1);
  }

  @Test
  @DisplayName("Le réglage de la fenêtre est celui annoncé")
  void thewindowSettingIsTheOneAnnounced() {
    assertThat(learned.windowMinutes()).isEqualTo(10);
  }
}

package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.diagnostic.domain.CauseSource;
import sn.ept.transdic1.diagnostic.service.CauseConfirmationService;
import sn.ept.transdic1.diagnostic.service.CauseWeightService;
import sn.ept.transdic1.diagnostic.service.DiagnosticService;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.EquipmentStatus;
import sn.ept.transdic1.inventory.domain.EquipmentType;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.SiteRepository;

/**
 * Cause confirmée par l'opérateur (M12) sur une vraie base : ce que la confirmation enregistre, ce
 * qu'un refus apporte, et ce que les poids du diagnostic deviennent.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CauseConfirmationTests {
  @Autowired private CauseConfirmationService confirmations;
  @Autowired private CauseWeightService weights;
  @Autowired private DiagnosticService diagnostics;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private jakarta.persistence.EntityManager em;

  private Equipment upstream;
  private Equipment downstream;
  private UUID alertId;
  private Instant opened;

  @BeforeEach
  void data() {
    jdbc.update("DELETE FROM cause_feedback");
    weights.invalidate();
    String unique = UUID.randomUUID().toString().substring(0, 8);
    Site site = sites.saveAndFlush(new Site("CAU-" + unique, "Site causes"));
    upstream = equipment("Routeur de coeur", site, 1);
    downstream = equipment("Commutateur Bloc A", site, 2);
    jdbc.update(
        "INSERT INTO equipment_link(id, equipment_id, upstream_id) VALUES (?, ?, ?)",
        UUID.randomUUID(),
        downstream.getId(),
        upstream.getId());
    em.flush();
    opened = Instant.now().minus(Duration.ofMinutes(2));
    // Une panne de collecte en amont, puis l'alerte à diagnostiquer en aval : le diagnostic
    // proposera « amont en alerte ».
    openAlert(upstream, "COLLECTION", "Plus de réponse", opened.minusSeconds(60));
    alertId = openAlert(downstream, "COLLECTION", "Plus de réponse", opened);
    em.flush();
  }

  private Equipment equipment(String name, Site site, int host) {
    return equipments.saveAndFlush(
        new Equipment(
            "CAU-EQ-" + UUID.randomUUID(),
            name,
            "10.89." + (Math.floorMod(name.hashCode(), 250) + 1) + "." + host,
            EquipmentType.ROUTER,
            EquipmentStatus.UP,
            site));
  }

  private UUID openAlert(Equipment on, String type, String message, Instant at) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO alert(id, equipment_id, occurrence_key, type, state, severity, message,"
            + " triggered_at) VALUES (?, ?, ?, ?, 'OPEN', 'CRITICAL', ?, ?)",
        id,
        on.getId(),
        "cau:" + UUID.randomUUID(),
        type,
        message,
        Timestamp.from(at));
    return id;
  }

  private int rows(boolean chosen) {
    em.flush();
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM cause_feedback WHERE alert_id = ? AND chosen = ?",
            Integer.class,
            alertId,
            chosen);
    return count == null ? 0 : count;
  }

  @Test
  @DisplayName("Le diagnostic propose bien une cause avant toute confirmation")
  void thediagnosticProposesAcauseBeforeAnyConfirmation() {
    var diagnostic = diagnostics.diagnose(alertId);

    assertThat(diagnostic.causesProbables()).isNotEmpty();
    assertThat(diagnostic.causesProbables())
        .extracting(c -> c.source())
        .contains(CauseSource.AMONT_EN_ALERTE);
    // Rien n'est encore appris, et la réponse le dit plutôt que de laisser croire le contraire.
    assertThat(diagnostic.poidsAppris()).isFalse();
    assertThat(diagnostic.causesConfirmees()).isZero();
    assertThat(diagnostic.causeConfirmee()).isNull();
  }

  @Test
  @DisplayName(
      "Ce que M12 apporte : toutes les propositions sont enregistrées, pas seulement la bonne")
  void whatM12addsEveryProposalIsRecordedNotJustTheRightOne() {
    var diagnostic = diagnostics.diagnose(alertId);
    int proposed = diagnostic.causesProbables().size();

    var outcome = confirmations.confirm(alertId, CauseSource.AMONT_EN_ALERTE, "noc-test");

    assertThat(outcome.recorded()).isEqualTo(proposed);
    assertThat(outcome.chosen()).isEqualTo(CauseSource.AMONT_EN_ALERTE);
    assertThat(rows(true)).isEqualTo(1);
    // Sans les refus, on ne saurait pas distinguer « a souvent raison » de « est souvent proposée
    // ».
    assertThat(rows(false)).isEqualTo(proposed - 1);
  }

  @Test
  @DisplayName("« Aucune de ces causes » est accepté, et n'enregistre que des refus")
  void noneOfTheseCausesIsAcceptedAndRecordsOnlyRefusals() {
    var diagnostic = diagnostics.diagnose(alertId);

    var outcome = confirmations.confirm(alertId, null, "noc-test");

    assertThat(outcome.chosen()).isNull();
    assertThat(rows(true)).isZero();
    assertThat(rows(false)).isEqualTo(diagnostic.causesProbables().size());
  }

  @Test
  @DisplayName("Reconfirmer remplace l'avis précédent au lieu de l'empiler")
  void reconfirmingReplacesTheEarlierVerdict() {
    var diagnostic = diagnostics.diagnose(alertId);
    int proposed = diagnostic.causesProbables().size();
    confirmations.confirm(alertId, CauseSource.AMONT_EN_ALERTE, "noc-test");
    em.flush();

    confirmations.confirm(alertId, null, "noc-test");

    assertThat(rows(true) + rows(false)).isEqualTo(proposed);
    assertThat(rows(true)).isZero();
  }

  @Test
  @DisplayName("La cause confirmée revient dans le diagnostic")
  void theconfirmedCauseComesBackInThediagnostic() {
    confirmations.confirm(alertId, CauseSource.AMONT_EN_ALERTE, "noc-test");
    em.flush();

    var diagnostic = diagnostics.diagnose(alertId);

    assertThat(diagnostic.causeConfirmee()).isEqualTo(CauseSource.AMONT_EN_ALERTE);
    assertThat(diagnostic.causesConfirmees()).isPositive();
  }

  @Test
  @DisplayName("Une cause qui n'est pas proposée est refusée, avec une raison lisible")
  void acauseNotProposedIsRefusedWithAreadableReason() {
    assertThatThrownBy(
            () -> confirmations.confirm(alertId, CauseSource.TENDANCE_SATURATION, "noc-test"))
        .hasMessageContaining("n'est pas proposée pour cette alerte");
  }

  @Test
  @DisplayName("Une alerte sans cause proposée ne peut pas être confirmée")
  void analertWithoutAproposedCauseCannotBeConfirmed() {
    // Une alerte seule, sans amont en panne, sans événement ni changement : rien à confirmer.
    Site other =
        sites.saveAndFlush(
            new Site("CAU2-" + UUID.randomUUID().toString().substring(0, 6), "Site nu"));
    Equipment lonely = equipment("Serveur isolé", other, 9);
    em.flush();
    UUID bare = openAlert(lonely, "ANOMALY", "Processeur inhabituel", Instant.now());
    em.flush();

    assertThatThrownBy(() -> confirmations.confirm(bare, null, "noc-test"))
        .hasMessageContaining("ne propose aucune cause");
  }

  @Test
  @DisplayName("Sous vingt confirmations, les poids restent ceux fixés à la main")
  void belowTwentyConfirmationsTheweightsStayHandSet() {
    confirmations.confirm(alertId, CauseSource.AMONT_EN_ALERTE, "noc-test");
    em.flush();
    weights.invalidate();

    var snapshot = weights.current();

    assertThat(snapshot.learned()).isFalse();
    assertThat(snapshot.confirmations()).isEqualTo(1);
    assertThat(snapshot.weights())
        .isEqualTo(sn.ept.transdic1.diagnostic.service.CauseScoring.WEIGHTS);
  }

  @Test
  @DisplayName("Au-delà de vingt confirmations, les poids viennent des réponses")
  void beyondTwentyConfirmationsTheweightsComeFromTheAnswers() {
    // Vingt-cinq confirmations où l'amont a été proposé et toujours refusé : son poids doit
    // descendre sous celui fixé à la main.
    for (int i = 0; i < 25; i++) {
      jdbc.update(
          "INSERT INTO cause_feedback(id, alert_id, source, strength, minutes_from_opening,"
              + " chosen, confirmed_at, confirmed_by) VALUES (?, ?, 'AMONT_EN_ALERTE', 1.0, 0,"
              + " false, now(), 'noc-test')",
          UUID.randomUUID(),
          openAlert(upstream, "ANOMALY", "Historique " + i, Instant.now()));
    }
    em.flush();
    weights.invalidate();

    var snapshot = weights.current();

    assertThat(snapshot.learned()).isTrue();
    assertThat(snapshot.confirmations()).isEqualTo(25);
    assertThat(snapshot.weights().get(CauseSource.AMONT_EN_ALERTE).weight())
        .isLessThan(
            sn.ept.transdic1.diagnostic.service.CauseScoring.WEIGHTS
                .get(CauseSource.AMONT_EN_ALERTE)
                .weight());
    // Le plancher de décroissance n'est pas appris : il dit la durabilité d'une preuve.
    assertThat(snapshot.weights().get(CauseSource.AMONT_EN_ALERTE).floor())
        .isEqualTo(
            sn.ept.transdic1.diagnostic.service.CauseScoring.WEIGHTS
                .get(CauseSource.AMONT_EN_ALERTE)
                .floor());
  }

  @Test
  @DisplayName("La force de la preuve est conservée avec le verdict")
  void thestrengthOfTheEvidenceIsKeptWithTheVerdict() {
    confirmations.confirm(alertId, CauseSource.AMONT_EN_ALERTE, "noc-test");
    em.flush();

    BigDecimal strength =
        jdbc.queryForObject(
            "SELECT strength::numeric FROM cause_feedback WHERE alert_id = ? AND chosen",
            BigDecimal.class,
            alertId);

    // Conservée pour pouvoir un jour apprendre autre chose que le poids par source, sans avoir
    // perdu l'historique.
    assertThat(strength).isNotNull();
    assertThat(strength.doubleValue()).isBetween(0.0, 1.0);
  }
}

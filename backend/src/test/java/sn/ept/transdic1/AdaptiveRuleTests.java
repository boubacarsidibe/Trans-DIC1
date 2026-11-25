package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import sn.ept.transdic1.alerting.domain.Alert;
import sn.ept.transdic1.alerting.domain.ComparisonOperator;
import sn.ept.transdic1.alerting.domain.Severity;
import sn.ept.transdic1.alerting.dto.AlertRuleRequest;
import sn.ept.transdic1.alerting.repository.AlertRepository;
import sn.ept.transdic1.alerting.service.AlertRuleService;
import sn.ept.transdic1.collection.dto.MeasureInput;
import sn.ept.transdic1.collection.service.MeasureIngestionService;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.EquipmentStatus;
import sn.ept.transdic1.inventory.domain.EquipmentType;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.SiteRepository;

/**
 * Seuil adaptatif de bout en bout (ADR 0049) : historique horaire en base, règle créée par le
 * service d'administration, mesures passées par l'ingestion.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AdaptiveRuleTests {
  @Autowired private MeasureIngestionService ingestion;
  @Autowired private AlertRuleService ruleService;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private AlertRepository alerts;
  @Autowired private JdbcTemplate jdbc;

  private Equipment busy;
  private Equipment quiet;
  private Instant now;

  @BeforeEach
  void data() {
    String unique = UUID.randomUUID().toString().substring(0, 8);
    Site site = sites.save(new Site("ADP-" + unique, "Site du seuil adaptatif"));
    busy = equipment(site, "chargé", 1);
    quiet = equipment(site, "calme", 2);
    now = Instant.now().truncatedTo(ChronoUnit.MINUTES).minus(1, ChronoUnit.MINUTES);
  }

  private Equipment equipment(Site site, String label, int host) {
    var created =
        equipments.save(
            new Equipment(
                "ADP-" + UUID.randomUUID(),
                "Serveur " + label,
                "10.249." + (Math.floorMod(UUID.randomUUID().hashCode(), 250) + 1) + "." + host,
                EquipmentType.SERVER,
                EquipmentStatus.UP,
                site));
    equipments.flush();
    return created;
  }

  /** Pointes horaires au même créneau sur les 27 derniers jours, légèrement bruitées. */
  private void history(Equipment target, double peak) {
    Instant slot = now.truncatedTo(ChronoUnit.HOURS);
    for (int day = 1; day <= 27; day++) {
      double max = peak + (day % 5) - 2;
      jdbc.update(
          "INSERT INTO metric_hourly(equipment_id, metric_key, hour_start, unit, sample_count,"
              + " min_value, max_value, avg_value) VALUES (?, 'cpu.usage', ?, '%', 60, ?, ?, ?)",
          target.getId(),
          Timestamp.from(slot.minus(day, ChronoUnit.DAYS)),
          max - 20,
          max,
          max - 10);
    }
  }

  private AlertRuleRequest request(
      String name, ComparisonOperator operator, boolean adaptive, String limit, Equipment... in) {
    return new AlertRuleRequest(
        name,
        "cpu.usage",
        operator,
        new BigDecimal("50"),
        null,
        "%",
        0L,
        Severity.WARNING,
        true,
        null,
        java.util.Arrays.stream(in).map(Equipment::getId).toList(),
        false,
        adaptive,
        null,
        limit == null ? null : new BigDecimal(limit));
  }

  private void send(Equipment target, String value) {
    ingestion.ingest(
        target.getId(),
        List.of(new MeasureInput("cpu.usage", new BigDecimal(value), "%", null, now)));
  }

  private List<Alert> alertsOf(Equipment target) {
    return alerts
        .findByEquipmentIdOrderByTriggeredAtDesc(
            target.getId(), org.springframework.data.domain.Pageable.unpaged())
        .getContent();
  }

  @Test
  void aBusyEquipmentIsNotAlertedAtItsUsualLevel() {
    history(busy, 70);
    ruleService.create(
        request("adp-" + UUID.randomUUID(), ComparisonOperator.GREATER_THAN, true, null, busy));

    // 72 % dépasse le seuil de départ (50) mais reste dans les pointes habituelles de ce serveur.
    send(busy, "72");

    assertThat(alertsOf(busy)).isEmpty();
  }

  @Test
  void aBusyEquipmentIsAlertedWellAboveItsUsualPeaksWithAnExplainedMessage() {
    history(busy, 70);
    ruleService.create(
        request("adp-" + UUID.randomUUID(), ComparisonOperator.GREATER_THAN, true, null, busy));

    send(busy, "95");

    var opened = alertsOf(busy);
    assertThat(opened).hasSize(1);
    assertThat(opened.get(0).getMessage())
        .contains("cpu.usage = 95 %")
        .contains("seuil appris")
        .contains("pointes habituelles");
  }

  @Test
  void aQuietEquipmentIsAlertedBelowTheStartingThreshold() {
    history(quiet, 10);
    ruleService.create(
        request("adp-" + UUID.randomUUID(), ComparisonOperator.GREATER_THAN, true, null, quiet));

    // 40 % reste sous le seuil de départ (50), mais quatre fois les pointes de ce serveur.
    send(quiet, "40");

    assertThat(alertsOf(quiet)).hasSize(1);
  }

  @Test
  void eachEquipmentLearnsItsOwnThresholdFromTheSameRule() {
    history(busy, 70);
    history(quiet, 10);
    var rule =
        ruleService.create(
            request(
                "adp-" + UUID.randomUUID(),
                ComparisonOperator.GREATER_THAN,
                true,
                null,
                busy,
                quiet));

    send(busy, "60");
    send(quiet, "12");

    var learned = ruleService.learned(rule.id());
    assertThat(learned).hasSize(2).allSatisfy(t -> assertThat(t.learned()).isTrue());
    var busyThreshold =
        learned.stream()
            .filter(t -> t.equipmentId().equals(busy.getId()))
            .findFirst()
            .orElseThrow();
    var quietThreshold =
        learned.stream()
            .filter(t -> t.equipmentId().equals(quiet.getId()))
            .findFirst()
            .orElseThrow();
    assertThat(busyThreshold.threshold()).isGreaterThan(new BigDecimal("70"));
    assertThat(quietThreshold.threshold()).isLessThan(new BigDecimal("30"));
    int hour = now.atZone(java.time.ZoneId.of("Africa/Dakar")).getHour();
    assertThat(busyThreshold.slot()).contains("vers " + hour + " h");
  }

  @Test
  void withoutHistoryTheStartingThresholdApplies() {
    ruleService.create(
        request("adp-" + UUID.randomUUID(), ComparisonOperator.GREATER_THAN, true, null, quiet));

    // Sous le seuil de départ (50) : rien, alors qu'aucune normale n'existe encore.
    send(quiet, "40");
    assertThat(alertsOf(quiet)).isEmpty();

    ingestion.ingest(
        quiet.getId(),
        List.of(
            new MeasureInput(
                "cpu.usage", new BigDecimal("60"), "%", null, now.plus(30, ChronoUnit.SECONDS))));
    var opened = alertsOf(quiet);
    assertThat(opened).hasSize(1);
    assertThat(opened.get(0).getMessage()).contains("seuil de départ");
  }

  @Test
  void theAbsoluteLimitOpensTheAlertWhateverWasLearned() {
    // Serveur saturé tout le mois : le modèle apprendrait que 97 % est normal.
    history(busy, 97);
    ruleService.create(
        request("adp-" + UUID.randomUUID(), ComparisonOperator.GREATER_THAN, true, "90", busy));

    send(busy, "96");

    var opened = alertsOf(busy);
    assertThat(opened).hasSize(1);
    assertThat(opened.get(0).getMessage()).contains("limite absolue");
  }

  @Test
  void changingTheRuleForgetsWhatWasLearned() {
    history(busy, 70);
    var rule =
        ruleService.create(
            request("adp-" + UUID.randomUUID(), ComparisonOperator.GREATER_THAN, true, null, busy));
    send(busy, "60");
    assertThat(ruleService.learned(rule.id())).hasSize(1);

    ruleService.update(
        rule.id(), request(rule.name(), ComparisonOperator.GREATER_THAN, true, "99", busy));

    assertThat(ruleService.learned(rule.id())).isEmpty();
  }

  @Test
  void aFixedRuleLearnsNothing() {
    history(busy, 70);
    var rule =
        ruleService.create(
            request(
                "fix-" + UUID.randomUUID(), ComparisonOperator.GREATER_THAN, false, null, busy));

    send(busy, "72");

    assertThat(alertsOf(busy)).hasSize(1);
    assertThat(ruleService.learned(rule.id())).isEmpty();
    assertThat(ruleService.get(rule.id()).adaptive()).isFalse();
  }

  @Test
  void equalityCannotBeAdaptive() {
    assertThatThrownBy(
            () ->
                ruleService.create(
                    request("eq-" + UUID.randomUUID(), ComparisonOperator.EQUAL, true, null, busy)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("égalité");
  }
}

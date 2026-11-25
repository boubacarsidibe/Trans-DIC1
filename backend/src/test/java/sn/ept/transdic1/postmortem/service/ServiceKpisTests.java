package sn.ept.transdic1.postmortem.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sn.ept.transdic1.postmortem.repository.PostMortemRows.KpiAlertRow;

/** MTTA, MTTR et MTBF sur des alertes écrites à la main. */
class ServiceKpisTests {
  private static final Instant T0 = Instant.parse("2026-11-02T08:00:00Z");
  private static final UUID ROUTER = UUID.randomUUID();
  private static final UUID SERVER = UUID.randomUUID();

  private static KpiAlertRow alert(
      UUID equipment,
      String type,
      String severity,
      int openedMin,
      Integer takenMin,
      Integer resolvedMin,
      String taker) {
    Instant opened = T0.plus(Duration.ofMinutes(openedMin));
    return new KpiAlertRow(
        UUID.randomUUID(),
        severity,
        type,
        opened,
        takenMin == null ? null : opened.plus(Duration.ofMinutes(takenMin)),
        resolvedMin == null ? null : opened.plus(Duration.ofMinutes(resolvedMin)),
        resolvedMin == null ? null : (takenMin == null ? "AUTOMATIC" : "MANUAL"),
        null,
        "Site A",
        equipment,
        equipment == ROUTER ? "Routeur" : "Serveur",
        taker);
  }

  private final List<KpiAlertRow> rows =
      List.of(
          alert(ROUTER, "COLLECTION", "CRITICAL", 0, 5, 60, "awa"),
          alert(ROUTER, "COLLECTION", "CRITICAL", 600, 25, 120, "moussa"),
          alert(SERVER, "THRESHOLD", "WARNING", 30, 10, 40, "awa"),
          alert(SERVER, "ANOMALY", "WARNING", 90, null, 15, null),
          alert(SERVER, "THRESHOLD", "INFO", 120, null, null, null));

  @Test
  @DisplayName("Moyenne et médiane de prise en charge et de résolution, part dans l'objectif")
  void overallFigures() {
    var f = ServiceKpis.figures(rows, 15);
    assertThat(f.alerts()).isEqualTo(5);
    assertThat(f.acknowledged()).isEqualTo(3);
    assertThat(f.resolved()).isEqualTo(4);
    assertThat(f.mttaMinutes()).isEqualTo(13.3); // (5 + 25 + 10) / 3
    assertThat(f.mttaMedianMinutes()).isEqualTo(10.0);
    assertThat(f.mttrMinutes()).isEqualTo(58.8); // (60 + 120 + 40 + 15) / 4
    assertThat(f.mttrMedianMinutes()).isEqualTo(50.0);
    assertThat(f.acknowledgedWithinTargetPercent()).isEqualTo(66.7);
    assertThat(f.resolvedAutomaticallyPercent()).isEqualTo(25.0);
  }

  @Test
  @DisplayName("Par opérateur et par sévérité")
  void groups() {
    var operators = ServiceKpis.byOperator(rows, 15);
    assertThat(operators).extracting(o -> o.username()).containsExactly("awa", "moussa");
    assertThat(operators.get(0).acknowledged()).isEqualTo(2);
    assertThat(operators.get(0).withinTarget()).isEqualTo(2);
    assertThat(ServiceKpis.bySeverity(rows, 15))
        .extracting(g -> g.key())
        .containsExactly("CRITICAL", "WARNING", "INFO");
  }

  @Test
  @DisplayName("MTBF : temps en service divisé par le nombre de pannes, rogné à la période")
  void reliability() {
    var equipments = ServiceKpis.equipments(rows, T0, T0.plus(Duration.ofHours(24)), 10);
    var router = equipments.get(0);
    assertThat(router.name()).isEqualTo("Routeur");
    assertThat(router.failures()).isEqualTo(2);
    assertThat(router.downtimeMinutes()).isEqualTo(180.0);
    // (1440 - 180) min / 2 pannes = 630 min = 10,5 h
    assertThat(router.mtbfHours()).isEqualTo(10.5);
    assertThat(router.availabilityPercent()).isEqualTo(87.5);
    assertThat(equipments.get(1).mtbfHours()).isNull();
  }

  @Test
  @DisplayName("Sans alerte, aucune moyenne n'est inventée")
  void noData() {
    var f = ServiceKpis.figures(List.of(), 15);
    assertThat(f.mttaMinutes()).isNull();
    assertThat(f.acknowledgedWithinTargetPercent()).isNull();
  }
}

package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.alerting.service.LevelShiftService;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.EquipmentStatus;
import sn.ept.transdic1.inventory.domain.EquipmentType;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.SiteRepository;

/**
 * Rupture de niveau (M5) sur une vraie base : ce qui ouvre une alerte, ce qui n'en ouvre pas, et ce
 * que l'alerte dit à qui la lit.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class LevelShiftAlertTests {
  @Autowired private LevelShiftService levelShift;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private jakarta.persistence.EntityManager em;

  private Equipment router;
  private Instant lastHour;

  @BeforeEach
  void data() {
    String unique = UUID.randomUUID().toString();
    Site site = sites.saveAndFlush(new Site("RUP-" + unique.substring(0, 8), "Site rupture"));
    router =
        equipments.saveAndFlush(
            new Equipment(
                "RUP-EQ-" + unique,
                "Routeur de collecte",
                "10.96."
                    + (Math.floorMod(unique.hashCode(), 250) + 1)
                    + "."
                    + (Math.floorMod(unique.substring(9).hashCode(), 250) + 1),
                EquipmentType.ROUTER,
                EquipmentStatus.UP,
                site));
    em.flush();
    lastHour = Instant.now().truncatedTo(ChronoUnit.HOURS).minus(Duration.ofHours(1));
  }

  private void hourly(Instant at, String key, String unit, double value) {
    jdbc.update(
        "INSERT INTO metric_hourly(equipment_id, metric_key, hour_start, unit, sample_count,"
            + " min_value, max_value, avg_value) VALUES (?, ?, ?, ?, 12, ?, ?, ?)"
            + " ON CONFLICT (equipment_id, metric_key, hour_start) DO UPDATE"
            + " SET avg_value = EXCLUDED.avg_value",
        router.getId(),
        key,
        Timestamp.from(at),
        unit,
        value,
        value,
        value);
  }

  /**
   * Remplit {@code hours} heures d'un indicateur, la plus ancienne d'abord, avec le niveau que
   * {@code level} donne pour chaque heure écoulée.
   */
  private void fill(
      String key, String unit, int hours, java.util.function.IntToDoubleFunction level) {
    for (int i = 0; i < hours; i++) {
      hourly(lastHour.minus(Duration.ofHours(hours - 1L - i)), key, unit, level.applyAsDouble(i));
    }
  }

  private List<String> alertMessages() {
    em.flush();
    return jdbc.queryForList(
        "SELECT message FROM alert WHERE equipment_id = ? AND type = 'ANOMALY'"
            + " AND state = 'OPEN' ORDER BY triggered_at",
        String.class,
        router.getId());
  }

  @Test
  @DisplayName("Un lien qui négocie 100 Mb/s au lieu de 1 Gb/s est signalé, avec la date")
  void alinkNegotiatingAHundredInsteadOfAThousandIsReported() {
    fill("net.speed", "Mb/s", 240, hour -> hour < 150 ? 1_000 : 100);

    int opened = levelShift.examine(router, Instant.now());

    assertThat(opened).isEqualTo(1);
    assertThat(alertMessages())
        .hasSize(1)
        .first(org.assertj.core.api.InstanceOfAssertFactories.STRING)
        .startsWith("Rupture de niveau sur Routeur de collecte")
        .contains("net.speed est descendu de 1000 Mb/s à 100 Mb/s (-90 %)")
        .contains("depuis le ")
        .contains("pas une pointe")
        .contains("pas pourquoi");
  }

  @Test
  @DisplayName("L'indicateur concerné est porté par l'alerte, pas seulement dit dans son message")
  void themetricConcernedIsCarriedByTheAlert() {
    fill("memory.usage", "%", 240, hour -> hour < 150 ? 40 : 70);

    levelShift.examine(router, Instant.now());
    em.flush();

    assertThat(
            jdbc.queryForList(
                "SELECT metric_instance FROM alert WHERE equipment_id = ? AND state = 'OPEN'",
                String.class,
                router.getId()))
        .containsExactly("memory.usage");
  }

  @Test
  @DisplayName("Un indicateur stable n'ouvre rien, même bruyant")
  void astableMetricOpensNothingEvenaNoisyOne() {
    var random = new Random(55L);
    fill("cpu.usage", "%", 240, hour -> 45 + random.nextGaussian() * 6);

    assertThat(levelShift.examine(router, Instant.now())).isZero();
    assertThat(alertMessages()).isEmpty();
  }

  @Test
  @DisplayName("Sans assez d'heures, rien n'est jugé")
  void withoutEnoughHoursNothingIsJudged() {
    fill("net.speed", "Mb/s", 60, hour -> hour < 30 ? 1_000 : 100);

    assertThat(levelShift.examine(router, Instant.now())).isZero();
    assertThat(alertMessages()).isEmpty();
  }

  @Test
  @DisplayName("Une alerte déjà ouverte n'est pas doublée")
  void analreadyOpenAlertIsNotDuplicated() {
    fill("net.speed", "Mb/s", 240, hour -> hour < 150 ? 1_000 : 100);
    levelShift.examine(router, Instant.now());

    assertThat(levelShift.examine(router, Instant.now())).isZero();
    assertThat(alertMessages()).hasSize(1);
  }

  @Test
  @DisplayName("Le niveau redevenu stable referme l'alerte, en disant laquelle des deux raisons")
  void astableLevelAgainClosesTheAlert() {
    fill("net.speed", "Mb/s", 240, hour -> hour < 150 ? 1_000 : 100);
    levelShift.examine(router, Instant.now());
    assertThat(alertMessages()).hasSize(1);

    // Le niveau d'avant revient : la série entière est de nouveau à 1 Gb/s.
    fill("net.speed", "Mb/s", 240, hour -> 1_000);
    levelShift.examine(router, Instant.now());

    assertThat(alertMessages()).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "SELECT e.comment FROM alert_event e JOIN alert a ON a.id = e.alert_id"
                    + " WHERE a.equipment_id = ? AND e.type = 'RESOLVED'",
                String.class,
                router.getId()))
        .contains("Niveau redevenu stable");
  }

  @Test
  @DisplayName("Une règle de seuil posée sur l'indicateur suffit : la rupture ne s'y ajoute pas")
  void athresholdRuleOnTheMetricIsEnoughNoShiftAlertIsAdded() {
    fill("net.speed", "Mb/s", 240, hour -> hour < 150 ? 1_000 : 100);
    UUID ruleId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO alert_rule(id, name, metric_type, operator, threshold,"
            + " confirmation_duration_seconds, initial_severity) VALUES (?, ?, 'net.speed',"
            + " 'LESS_THAN', 500, 60, 'WARNING')",
        ruleId,
        "Débit du lien " + ruleId);
    jdbc.update(
        "INSERT INTO alert(id, equipment_id, rule_id, occurrence_key, type, state, severity,"
            + " message, triggered_at) VALUES (?, ?, ?, ?, 'THRESHOLD', 'OPEN', 'WARNING',"
            + " 'Debit sous le seuil', now())",
        UUID.randomUUID(),
        router.getId(),
        ruleId,
        ruleId + ":" + router.getId() + ":" + System.currentTimeMillis());
    em.flush();

    assertThat(levelShift.examine(router, Instant.now())).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM alert WHERE equipment_id = ? AND type = 'ANOMALY'",
                Integer.class,
                router.getId()))
        .isZero();
  }

  @Test
  @DisplayName("Une machine refaite à neuf n'ouvre pas une alerte par indicateur")
  void amachineRebuiltFromScratchDoesNotOpenOneAlertPerMetric() {
    // Cinq indicateurs changent de niveau à la même heure : un seul fait, pas cinq.
    for (String key : List.of("cpu.usage", "memory.usage", "disk.usage", "net.in", "net.out")) {
      fill(key, "%", 240, hour -> hour < 150 ? 20 : 70);
    }

    int opened = levelShift.examine(router, Instant.now());

    assertThat(opened).isEqualTo(3); // alerting.level-shift.max-per-equipment
    assertThat(alertMessages()).hasSize(3);
  }

  @Test
  @DisplayName("Un passage sur le parc n'échoue pas sur un équipement sans mesure")
  void apassOverTheFleetDoesNotFailOnanEquipmentWithoutMetrics() {
    assertThat(levelShift.scan()).isGreaterThanOrEqualTo(0);
  }

  @Test
  @DisplayName("Les séries lues gardent l'heure de chaque point et l'unité")
  void theseriesReadKeepTheHourOfEachPointAndTheUnit() {
    fill("net.speed", "Mb/s", 240, hour -> 1_000);
    // Les indicateurs à 0 ou 100 sont écartés : leur chute est déjà une alerte à elle seule.
    fill("availability", "%", 240, hour -> 100);

    var series = levelShift.series(router.getId(), Instant.now());

    assertThat(series).hasSize(1);
    assertThat(series.get(0).key()).isEqualTo("net.speed");
    assertThat(series.get(0).unit()).isEqualTo("Mb/s");
    assertThat(series.get(0).hours()).hasSize(240).isSorted();
  }
}

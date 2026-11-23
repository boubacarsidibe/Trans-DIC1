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
import sn.ept.transdic1.alerting.service.MultivariateAnomalyService;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.EquipmentStatus;
import sn.ept.transdic1.inventory.domain.EquipmentType;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.SiteRepository;

/**
 * Anomalie à plusieurs mesures (M2) sur une vraie base : ce que la forêt d'isolement voit, ce
 * qu'elle laisse passer, et ce qu'elle refuse de juger faute d'historique.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MultivariateAnomalyTests {
  @Autowired private MultivariateAnomalyService multivariate;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private jakarta.persistence.EntityManager em;

  private Equipment server;
  private Instant lastHour;

  @BeforeEach
  void data() {
    String unique = UUID.randomUUID().toString();
    Site site = sites.saveAndFlush(new Site("MUL-" + unique.substring(0, 8), "Site ensemble"));
    server =
        equipments.saveAndFlush(
            new Equipment(
                "MUL-EQ-" + unique,
                "Serveur applicatif",
                "10.95."
                    + (Math.floorMod(unique.hashCode(), 250) + 1)
                    + "."
                    + (Math.floorMod(unique.substring(9).hashCode(), 250) + 1),
                EquipmentType.SERVER,
                EquipmentStatus.UP,
                site));
    em.flush();
    lastHour = Instant.now().truncatedTo(ChronoUnit.HOURS).minus(Duration.ofHours(1));
  }

  private void hourly(Instant at, String key, double value) {
    jdbc.update(
        "INSERT INTO metric_hourly(equipment_id, metric_key, hour_start, unit, sample_count,"
            + " min_value, max_value, avg_value) VALUES (?, ?, ?, '%', 12, ?, ?, ?)"
            + " ON CONFLICT (equipment_id, metric_key, hour_start) DO UPDATE"
            + " SET avg_value = EXCLUDED.avg_value",
        server.getId(), key, Timestamp.from(at), value, value, value);
  }

  /**
   * Dix jours d'habitude : la latence suit la charge du processeur, la mémoire vit sa vie. Le
   * générateur est à graine fixe, pour que le verdict ne change pas d'une exécution à l'autre.
   */
  private void habitude(int hours) {
    var random = new Random(4_242L);
    for (int h = hours; h >= 1; h--) {
      Instant at = lastHour.minus(Duration.ofHours(h));
      double cpu = 30 + random.nextDouble() * 40;
      hourly(at, "cpu.usage", cpu);
      hourly(at, "memory.usage", 55 + random.nextGaussian() * 4);
      hourly(at, "probe.latency", cpu / 3 + random.nextGaussian() * 0.5);
    }
  }

  private void derniereHeure(double cpu, double memory, double latency) {
    hourly(lastHour, "cpu.usage", cpu);
    hourly(lastHour, "memory.usage", memory);
    hourly(lastHour, "probe.latency", latency);
  }

  private List<String> alertMessages() {
    em.flush();
    return jdbc.queryForList(
        "SELECT message FROM alert WHERE equipment_id = ? AND type = 'ANOMALY'"
            + " AND state = 'OPEN' ORDER BY triggered_at",
        String.class,
        server.getId());
  }

  @Test
  @DisplayName("Sans assez d'heures complètes, rien n'est jugé")
  void withoutEnoughCompleteHoursNothingIsJudged() {
    habitude(40); // moins que les 72 heures exigées
    derniereHeure(35, 55, 22);

    assertThat(multivariate.examine(server, Instant.now())).isZero();
    assertThat(alertMessages()).isEmpty();
  }

  @Test
  @DisplayName("Une heure ordinaire n'ouvre rien")
  void anordinaryHourOpensNothing() {
    habitude(240);
    derniereHeure(45, 56, 15); // cohérent : latence proportionnelle à la charge

    assertThat(multivariate.examine(server, Instant.now())).isZero();
    assertThat(alertMessages()).isEmpty();
  }

  @Test
  @DisplayName("Une combinaison inédite est signalée, alors qu'aucune mesure ne sort de sa plage")
  void anunprecedentedCombinationIsReported() {
    habitude(240);
    // Processeur à 35 % : banal. Latence à 23 ms : vue aussi, mais seulement à pleine charge.
    // Les deux ensemble ne se rencontrent jamais.
    derniereHeure(35, 56, 23);

    int opened = multivariate.examine(server, Instant.now());

    assertThat(opened).isEqualTo(1);
    assertThat(alertMessages())
        .hasSize(1)
        .first(org.assertj.core.api.InstanceOfAssertFactories.STRING)
        .startsWith("État inhabituel de Serveur applicatif")
        .contains("aucune ne dépasse sa propre normale")
        .contains("C'est un constat, pas une cause");
  }

  @Test
  @DisplayName("L'alerte cite les indicateurs les plus éloignés de leur habitude")
  void thealertCitesTheMetricsFurthestFromTheirHabit() {
    habitude(240);
    derniereHeure(35, 56, 23);

    multivariate.examine(server, Instant.now());

    assertThat(alertMessages())
        .first(org.assertj.core.api.InstanceOfAssertFactories.STRING)
        .contains("probe.latency 23,0");
  }

  @Test
  @DisplayName("Une alerte déjà ouverte n'est pas doublée")
  void analreadyOpenAlertIsNotDuplicated() {
    habitude(240);
    derniereHeure(35, 56, 23);
    multivariate.examine(server, Instant.now());

    assertThat(multivariate.examine(server, Instant.now())).isZero();
    assertThat(alertMessages()).hasSize(1);
  }

  @Test
  @DisplayName("Le retour à la normale referme l'alerte")
  void areturnToNormalClosesTheAlert() {
    habitude(240);
    derniereHeure(35, 56, 23);
    multivariate.examine(server, Instant.now());
    assertThat(alertMessages()).hasSize(1);

    derniereHeure(45, 56, 15);
    multivariate.examine(server, Instant.now());

    assertThat(alertMessages()).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "SELECT resolution_type FROM alert WHERE equipment_id = ? AND type = 'ANOMALY'",
                String.class,
                server.getId()))
        .isEqualTo("AUTOMATIC");
  }

  @Test
  @DisplayName("Une alerte porte déjà sur un indicateur : on ne la double pas d'un état d'ensemble")
  void whenAMetricAlertIsAlreadyOpenNoEnsembleAlertIsAdded() {
    habitude(240);
    derniereHeure(35, 56, 23);
    // Une alerte d'anomalie sur un seul indicateur, comme le moteur de l'ADR 0015 en pose.
    jdbc.update(
        "INSERT INTO alert(id, equipment_id, occurrence_key, type, state, severity, message,"
            + " triggered_at) VALUES (?, ?, ?, 'ANOMALY', 'OPEN', 'WARNING', 'CPU', now())",
        UUID.randomUUID(),
        server.getId(),
        "anomaly:cpu.usage:" + UUID.randomUUID());
    em.flush();

    assertThat(multivariate.examine(server, Instant.now())).isZero();
  }

  @Test
  @DisplayName("Un indicateur relevé par épisodes est laissé de côté, pas l'équipement")
  void asporadicallyMeasuredMetricIsLeftOutNotTheEquipment() {
    habitude(240);
    // Un indicateur relevé onze heures seulement, comme un modèle SNMP appliqué la veille.
    for (int h = 1; h <= 11; h++) {
      hourly(lastHour.minus(Duration.ofHours(h)), "disk.usage", 70 + h);
    }
    derniereHeure(45, 56, 15);

    var history = multivariate.history(server.getId(), Instant.now());

    // Sans garde-fou, les onze heures où le disque est connu seraient les seules « complètes »
    // et la machine ne serait plus jugée du tout.
    assertThat(history).isNotNull();
    assertThat(history.keys()).doesNotContain("disk.usage");
    assertThat(history.rows().length).isGreaterThan(200);
  }

  @Test
  @DisplayName("Les heures incomplètes sont écartées plutôt que comblées")
  void incompleteHoursAreDiscardedRatherThanFilled() {
    habitude(240);
    // Dix heures où la mémoire manque : elles ne doivent pas entrer dans le modèle.
    for (int h = 300; h < 310; h++) {
      Instant at = lastHour.minus(Duration.ofHours(h));
      hourly(at, "cpu.usage", 40);
      hourly(at, "probe.latency", 13);
    }
    derniereHeure(45, 56, 15);

    var history = multivariate.history(server.getId(), Instant.now());

    assertThat(history).isNotNull();
    assertThat(history.keys()).containsExactly("cpu.usage", "memory.usage", "probe.latency");
    // 240 heures complètes, plus la dernière : les dix incomplètes sont restées dehors.
    assertThat(history.rows()).hasDimensions(241, 3);
  }
}

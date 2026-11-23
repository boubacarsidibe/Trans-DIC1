package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
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
import sn.ept.transdic1.alerting.service.BandDetectionService;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.EquipmentStatus;
import sn.ept.transdic1.inventory.domain.EquipmentType;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.SiteRepository;

/**
 * Bande de tolérance sur les taux réseau (M6) sur une vraie base : ce qui ouvre une alerte, ce qui
 * n'en ouvre pas, et que l'état est bien repris d'un passage au suivant.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class BandDetectionTests {
  @Autowired private BandDetectionService bands;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private jakarta.persistence.EntityManager em;

  private Equipment switchA;
  private Instant start;

  @BeforeEach
  void data() {
    jdbc.update("DELETE FROM metric_band");
    String unique = UUID.randomUUID().toString().substring(0, 8);
    Site site = sites.saveAndFlush(new Site("BND-" + unique, "Site bande"));
    switchA =
        equipments.saveAndFlush(
            new Equipment(
                "BND-EQ-" + UUID.randomUUID(),
                "Commutateur Bloc A",
                "10.93." + (Math.floorMod(unique.hashCode(), 250) + 1) + ".1",
                EquipmentType.SWITCH,
                EquipmentStatus.UP,
                site));
    em.flush();
    start =
        Instant.now()
            .minus(Duration.ofMinutes(50))
            .truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
  }

  private void measure(String key, Instant at, double value) {
    jdbc.update(
        "INSERT INTO metric(equipment_id, metric_key, metric_value, unit, quality,"
            + " collected_at, received_at) VALUES (?, ?, ?, 'Mb/s', 'GOOD', ?, ?)",
        switchA.getId(),
        key,
        BigDecimal.valueOf(value),
        Timestamp.from(at),
        Timestamp.from(at));
  }

  /** Trente mesures ordinaires, une toutes les minutes : de quoi construire la bande. */
  private void ordinary(String key, int count, double level, double noise, long seed) {
    var random = new Random(seed);
    for (int i = 0; i < count; i++) {
      measure(key, start.plus(Duration.ofMinutes(i)), level + random.nextGaussian() * noise);
    }
    em.flush();
  }

  private List<String> alertMessages() {
    em.flush();
    return jdbc.queryForList(
        "SELECT message FROM alert WHERE equipment_id = ? AND state = 'OPEN'"
            + " AND occurrence_key LIKE 'band:%' ORDER BY triggered_at",
        String.class, switchA.getId());
  }

  @Test
  @DisplayName("Du trafic ordinaire n'ouvre rien, même sur trente mesures")
  void ordinaryTrafficOpensNothing() {
    ordinary("net.in[Gi0/1]", 30, 500, 30, 3L);

    assertThat(bands.scan()).isZero();
    assertThat(alertMessages()).isEmpty();
  }

  @Test
  @DisplayName("Ce que M6 apporte : une normale utilisable après une vingtaine de mesures")
  void whatM6addsAusableNormalAfterTwentySamples() {
    ordinary("net.in[Gi0/1]", 30, 500, 30, 5L);
    bands.scan();

    // La détection par créneau exigerait six moyennes horaires au même créneau sur vingt-huit
    // jours, soit des semaines. Ici, la bande juge après une demi-heure.
    assertThat(
            jdbc.queryForObject(
                "SELECT samples FROM metric_band WHERE equipment_id = ? AND metric_key = ?",
                Integer.class,
                switchA.getId(),
                "net.in[Gi0/1]"))
        .isGreaterThanOrEqualTo(20);
  }

  @Test
  @DisplayName("Trois mesures de suite hors bande ouvrent une alerte qui dit la plage apprise")
  void threeConsecutiveSamplesOutsideOpenAnalert() {
    ordinary("net.in[Gi0/1]", 30, 500, 30, 7L);
    bands.scan();
    for (int i = 0; i < 3; i++) {
      measure("net.in[Gi0/1]", start.plus(Duration.ofMinutes(31L + i)), 9_000);
    }
    em.flush();

    int opened = bands.scan();

    assertThat(opened).isEqualTo(1);
    assertThat(alertMessages())
        .hasSize(1)
        .first(org.assertj.core.api.InstanceOfAssertFactories.STRING)
        .startsWith("net.in[Gi0/1] au-dessus de sa plage habituelle")
        .contains("depuis 3 mesures")
        .contains("ce port tient d'ordinaire entre")
        .contains("pas sur un seuil réglé à la main");
  }

  @Test
  @DisplayName("Une seule mesure hors bande n'ouvre rien")
  void asingleSampleOutsideOpensNothing() {
    ordinary("net.in[Gi0/1]", 30, 500, 30, 11L);
    bands.scan();
    measure("net.in[Gi0/1]", start.plus(Duration.ofMinutes(31)), 9_000);
    em.flush();

    assertThat(bands.scan()).isZero();
    assertThat(alertMessages()).isEmpty();
  }

  @Test
  @DisplayName("Le retour dans la plage referme l'alerte, après plusieurs mesures")
  void thereturnIntoTheBandClosesThealert() {
    ordinary("net.in[Gi0/1]", 30, 500, 30, 13L);
    bands.scan();
    for (int i = 0; i < 3; i++) {
      measure("net.in[Gi0/1]", start.plus(Duration.ofMinutes(31L + i)), 9_000);
    }
    em.flush();
    bands.scan();
    assertThat(alertMessages()).hasSize(1);

    for (int i = 0; i < 3; i++) {
      measure("net.in[Gi0/1]", start.plus(Duration.ofMinutes(40L + i)), 500);
    }
    em.flush();
    bands.scan();

    assertThat(alertMessages()).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "SELECT resolution_type FROM alert WHERE equipment_id = ?"
                    + " AND occurrence_key LIKE 'band:%'",
                String.class, switchA.getId()))
        .isEqualTo("AUTOMATIC");
  }

  @Test
  @DisplayName("L'état est repris d'un passage au suivant, et chaque mesure n'est vue qu'une fois")
  void thestateIsResumedAndEachSampleIsSeenOnce() {
    ordinary("net.in[Gi0/1]", 30, 500, 30, 17L);
    bands.scan();
    int afterFirst =
        jdbc.queryForObject(
            "SELECT samples FROM metric_band WHERE equipment_id = ? AND metric_key = ?",
            Integer.class,
            switchA.getId(),
            "net.in[Gi0/1]");

    bands.scan();

    // Un second passage sans mesure neuve ne doit rien réapprendre : sinon la normale se
    // construirait sur les mêmes valeurs comptées dix fois.
    assertThat(
            jdbc.queryForObject(
                "SELECT samples FROM metric_band WHERE equipment_id = ? AND metric_key = ?",
                Integer.class,
                switchA.getId(),
                "net.in[Gi0/1]"))
        .isEqualTo(afterFirst);
  }

  @Test
  @DisplayName("Avant vingt mesures, rien n'est jugé")
  void beforeTwentySamplesNothingIsJudged() {
    ordinary("net.in[Gi0/1]", 10, 500, 30, 19L);
    bands.scan();
    for (int i = 0; i < 3; i++) {
      measure("net.in[Gi0/1]", start.plus(Duration.ofMinutes(11L + i)), 9_000);
    }
    em.flush();

    assertThat(bands.scan()).isZero();
    assertThat(alertMessages()).isEmpty();
  }

  @Test
  @DisplayName("Seuls les taux réseau et la qualité des sondes sont surveillés")
  void onlyNetworkRatesAndProbeQualityAreWatched() {
    ordinary("cpu.usage", 30, 50, 3, 23L);
    for (int i = 0; i < 3; i++) {
      measure("cpu.usage", start.plus(Duration.ofMinutes(31L + i)), 99);
    }
    em.flush();

    // Le processeur a peu d'instances et sa normale par créneau se construit sans peine : la bande
    // n'y apporterait rien et doublerait les alertes.
    assertThat(bands.scan()).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM metric_band WHERE equipment_id = ?",
                Integer.class,
                switchA.getId()))
        .isZero();
  }

  @Test
  @DisplayName("Deux ports du même commutateur ont chacun leur plage")
  void twoportsOfTheSameSwitchHaveTheirOwnBand() {
    ordinary("net.in[Gi0/1]", 30, 500, 20, 29L);
    ordinary("net.in[Gi0/2]", 30, 20, 2, 31L);
    bands.scan();
    // 500 Mb/s est ordinaire sur le premier port, énorme sur le second.
    for (int i = 0; i < 3; i++) {
      measure("net.in[Gi0/2]", start.plus(Duration.ofMinutes(31L + i)), 500);
    }
    em.flush();

    assertThat(bands.scan()).isEqualTo(1);
    assertThat(alertMessages())
        .hasSize(1)
        .first(org.assertj.core.api.InstanceOfAssertFactories.STRING)
        .startsWith("net.in[Gi0/2]");
  }

  @Test
  @DisplayName("Un passage sur un parc sans mesure ne casse pas")
  void apassOveranemptyFleetDoesNotBreak() {
    assertThat(bands.scan()).isZero();
  }
}

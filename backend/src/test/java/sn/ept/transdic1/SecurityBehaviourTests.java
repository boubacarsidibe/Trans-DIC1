package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.EquipmentStatus;
import sn.ept.transdic1.inventory.domain.EquipmentType;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.SiteRepository;
import sn.ept.transdic1.threat.dto.LogBatch;
import sn.ept.transdic1.threat.dto.LogBatch.LogEntry;
import sn.ept.transdic1.threat.service.SecurityBehaviourService;
import sn.ept.transdic1.threat.service.SecurityIngestionService;

/**
 * Comportement inhabituel (S5) sur une vraie base : connexion d'un compte depuis une adresse jamais
 * vue, et volume d'une heure très éloigné de l'habitude du même créneau.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SecurityBehaviourTests {
  @Autowired private SecurityBehaviourService behaviour;
  @Autowired private SecurityIngestionService ingestion;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private jakarta.persistence.EntityManager em;

  private Equipment server;

  @BeforeEach
  void data() {
    String unique = UUID.randomUUID().toString();
    Site site = sites.saveAndFlush(new Site("CMP-" + unique.substring(0, 8), "Site comportement"));
    server =
        equipments.saveAndFlush(
            new Equipment(
                "CMP-EQ-" + unique,
                "Serveur de fichiers",
                "10.91."
                    + (Math.floorMod(unique.hashCode(), 250) + 1)
                    + "."
                    + (Math.floorMod(unique.substring(9).hashCode(), 250) + 1),
                EquipmentType.SERVER,
                EquipmentStatus.UP,
                site));
    em.flush();
  }

  /** Écrit directement une ligne de journal passée, sans passer par le jugement. */
  private void past(String kind, Instant when, String address, String username) {
    jdbc.update(
        "INSERT INTO security_event (id, equipment_id, occurred_at, received_at, kind,"
            + " source_address, username, score) VALUES (?, ?, ?, ?, ?, ?, ?, 0)",
        UUID.randomUUID(),
        server.getId(),
        Timestamp.from(when),
        Timestamp.from(when),
        kind,
        address,
        username);
  }

  private List<String> alertMessages() {
    em.flush();
    return jdbc.queryForList(
        "SELECT message FROM alert WHERE equipment_id = ? AND type = 'SECURITY'"
            + " AND state = 'OPEN' ORDER BY triggered_at",
        String.class,
        server.getId());
  }

  private void login(String address, String username) {
    em.flush();
    ingestion.ingest(
        server.getId(),
        new LogBatch(
            List.of(
                new LogEntry(
                    Instant.now().minusSeconds(5),
                    "AUTH_SUCCESS",
                    address,
                    username,
                    null,
                    null,
                    null))));
  }

  /** Donne au compte une habitude : plusieurs connexions passées depuis deux adresses. */
  private void habitude(String username) {
    Instant base = Instant.now().minus(Duration.ofDays(3));
    for (int i = 0; i < 6; i++) {
      past(
          "AUTH_SUCCESS",
          base.plus(Duration.ofHours(i)),
          i % 2 == 0 ? "10.1.2.3" : "10.1.2.4",
          username);
    }
  }

  @Test
  @DisplayName("Un compte connu qui se connecte depuis une adresse jamais vue est signalé")
  void aKnownAccountLoggingInFromAnAddressNeverSeenIsReported() {
    habitude("awa");

    login("203.0.113.55", "awa");

    assertThat(alertMessages())
        .hasSize(1)
        .first(org.assertj.core.api.InstanceOfAssertFactories.STRING)
        .startsWith("Connexion de awa sur Serveur de fichiers depuis 203.0.113.55")
        .contains("jamais connecté", "6 connexions depuis 2 autres adresses")
        .contains("À confirmer auprès de la personne");
    assertThat(
            jdbc.queryForObject(
                "SELECT severity FROM alert WHERE equipment_id = ? AND type = 'SECURITY'",
                String.class,
                server.getId()))
        .isEqualTo("WARNING");
  }

  @Test
  @DisplayName("Une connexion depuis une adresse habituelle ne dit rien")
  void aLoginFromAnUsualAddressSaysNothing() {
    habitude("awa");

    login("10.1.2.3", "awa");

    assertThat(alertMessages()).isEmpty();
  }

  @Test
  @DisplayName("Un compte dont on ne connaît pas encore les habitudes n'est pas jugé")
  void anAccountWhoseHabitsAreNotKnownYetIsNotJudged() {
    // Deux connexions seulement : trop peu pour parler d'habitude (seuil à 5).
    Instant base = Instant.now().minus(Duration.ofDays(2));
    past("AUTH_SUCCESS", base, "10.1.2.3", "nouveau");
    past("AUTH_SUCCESS", base.plusSeconds(3600), "10.1.2.3", "nouveau");

    login("203.0.113.56", "nouveau");

    assertThat(alertMessages()).isEmpty();
  }

  @Test
  @DisplayName("Deux connexions de suite depuis la même adresse nouvelle n'empilent qu'une alerte")
  void twoLoginsInARowFromTheSameNewAddressStackOnlyOneAlert() {
    habitude("awa");

    login("203.0.113.57", "awa");
    login("203.0.113.57", "awa");

    assertThat(alertMessages()).hasSize(1);
  }

  // ------------------------------------------------------------------- volume inhabituel

  /** Début de la dernière heure complète, celle que l'examen horaire regarde. */
  private Instant lastHour() {
    return Instant.now().truncatedTo(ChronoUnit.HOURS).minus(Duration.ofHours(1));
  }

  private void habitudeHoraire(String kind, int perDay, int days) {
    for (int day = 1; day <= days; day++) {
      Instant slot = lastHour().minus(Duration.ofDays(day));
      for (int i = 0; i < perDay; i++) {
        past(kind, slot.plusSeconds(60L * i), "10.1.2." + (i % 20), "compte" + i);
      }
    }
  }

  private void heureEcoulee(String kind, int count) {
    Instant slot = lastHour();
    for (int i = 0; i < count; i++) {
      past(kind, slot.plusSeconds(10L * i), "198.51.100." + (i % 200), "compte" + i);
    }
  }

  @Test
  @DisplayName("Une heure très au-dessus de l'habitude du créneau ouvre un avertissement")
  void anHourFarAboveTheUsualForThatSlotOpensAWarning() {
    habitudeHoraire("AUTH_FAILURE", 2, 10);
    heureEcoulee("AUTH_FAILURE", 300);
    em.flush();

    int opened = behaviour.scanUnusualVolume();

    assertThat(opened).isEqualTo(1);
    assertThat(alertMessages())
        .hasSize(1)
        .first(org.assertj.core.api.InstanceOfAssertFactories.STRING)
        .startsWith("Activité inhabituelle sur Serveur de fichiers : 300 échecs")
        .contains("au même créneau les jours précédents", "10 jours comparés");
  }

  @Test
  @DisplayName("Une heure ordinaire ne dit rien")
  void anOrdinaryHourSaysNothing() {
    habitudeHoraire("AUTH_FAILURE", 30, 10);
    heureEcoulee("AUTH_FAILURE", 32);
    em.flush();

    assertThat(behaviour.scanUnusualVolume()).isZero();
    assertThat(alertMessages()).isEmpty();
  }

  @Test
  @DisplayName("Quelques événements sur un serveur d'ordinaire muet ne suffisent pas")
  void aFewEventsOnAnUsuallySilentServerAreNotEnough() {
    // L'habitude est à zéro, mais six échecs ne valent pas qu'on réveille quelqu'un.
    habitudeHoraire("AUTH_FAILURE", 0, 10);
    past("AUTH_FAILURE", lastHour().minus(Duration.ofDays(10)), "10.1.2.3", "awa");
    heureEcoulee("AUTH_FAILURE", 6);
    em.flush();

    assertThat(behaviour.scanUnusualVolume()).isZero();
    assertThat(alertMessages()).isEmpty();
  }

  @Test
  @DisplayName("Sans historique, aucun jugement")
  void withoutHistoryNoJudgement() {
    heureEcoulee("AUTH_FAILURE", 300);
    em.flush();

    // Tout l'historique disponible est l'heure elle-même : il n'y a rien à quoi comparer.
    assertThat(behaviour.scanUnusualVolume()).isZero();
  }
}

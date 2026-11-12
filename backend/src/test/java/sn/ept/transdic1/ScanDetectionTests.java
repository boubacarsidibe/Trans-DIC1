package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;

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
import sn.ept.transdic1.flow.receiver.FlowRecord;
import sn.ept.transdic1.flow.service.FlowCollectionService;
import sn.ept.transdic1.flow.service.FlowObservations;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.EquipmentStatus;
import sn.ept.transdic1.inventory.domain.EquipmentType;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.SiteRepository;

/**
 * Balayages et inondations (S6) de bout en bout : des flux reçus jusqu'à l'alerte de sécurité
 * ouverte, en passant par l'événement commun qui relie les deux modules.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ScanDetectionTests {
  @Autowired private FlowObservations observations;
  @Autowired private FlowCollectionService collection;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private jakarta.persistence.EntityManager em;

  private Equipment router;

  @BeforeEach
  void data() {
    String unique = UUID.randomUUID().toString();
    Site site = sites.saveAndFlush(new Site("SCN-" + unique.substring(0, 8), "Site balayage"));
    router =
        equipments.saveAndFlush(
            new Equipment(
                "SCN-EQ-" + unique,
                "Routeur de bordure",
                "10.93."
                    + (Math.floorMod(unique.hashCode(), 250) + 1)
                    + "."
                    + (Math.floorMod(unique.substring(9).hashCode(), 250) + 1),
                EquipmentType.ROUTER,
                EquipmentStatus.UP,
                site));
    em.flush();
    observations.drain();
  }

  private FlowRecord flow(String source, String destination, int port, long bytes) {
    return new FlowRecord(source, destination, 6, 40000, port, bytes, 1, 1, 2);
  }

  private List<String> alertMessages() {
    em.flush();
    return jdbc.queryForList(
        "SELECT message FROM alert WHERE equipment_id = ? AND type = 'SECURITY'"
            + " AND state = 'OPEN' ORDER BY triggered_at",
        String.class,
        router.getId());
  }

  private String severity() {
    return jdbc.queryForObject(
        "SELECT severity FROM alert WHERE equipment_id = ? AND type = 'SECURITY'",
        String.class,
        router.getId());
  }

  @Test
  @DisplayName("Un balayage d'adresses ouvre un avertissement qui nomme la source")
  void anAddressScanOpensAWarningNamingTheSource() {
    for (int i = 0; i < 240; i++) {
      observations.add(
          router.getId(), flow("10.9.9.9", "10.1." + (i / 250) + "." + i % 250, 445, 60));
    }

    assertThat(collection.detectScans()).isEqualTo(1);

    assertThat(alertMessages())
        .hasSize(1)
        .first(org.assertj.core.api.InstanceOfAssertFactories.STRING)
        .startsWith("Balayage d'adresses depuis 10.9.9.9, vu par Routeur de bordure")
        .contains("240 machines contactées", "répartition 1,00 sur 1", "échanges minuscules");
    assertThat(severity()).isEqualTo("WARNING");
  }

  @Test
  @DisplayName("Un balayage de ports ouvre un avertissement distinct")
  void aPortScanOpensASeparateWarning() {
    for (int port = 1; port <= 150; port++) {
      observations.add(router.getId(), flow("10.9.9.8", "10.1.0.5", port, 50));
    }

    assertThat(collection.detectScans()).isEqualTo(1);

    assertThat(alertMessages())
        .first(org.assertj.core.api.InstanceOfAssertFactories.STRING)
        .startsWith("Balayage de ports depuis 10.9.9.8")
        .contains("150 ports frappés");
  }

  @Test
  @DisplayName("Une inondation ouvre une alerte critique")
  void afloodOpensACriticalAlert() {
    for (int i = 0; i < 260; i++) {
      observations.add(
          router.getId(), flow("203.0." + (i / 250) + "." + i % 250, "10.1.0.50", 443, 60));
    }

    assertThat(collection.detectScans()).isGreaterThanOrEqualTo(1);

    assertThat(alertMessages())
        .anySatisfy(
            message -> {
              assertThat(message).startsWith("Inondation vers 10.1.0.50");
              assertThat(message).contains("260 sources différentes", "Déni de service probable");
            });
    assertThat(
            jdbc.queryForList(
                "SELECT severity FROM alert WHERE equipment_id = ? AND type = 'SECURITY'",
                String.class,
                router.getId()))
        .contains("CRITICAL");
  }

  @Test
  @DisplayName("Un trafic ordinaire n'ouvre rien")
  void ordinaryTrafficOpensNothing() {
    // Une dizaine de machines qui échangent vraiment : ni balayage ni inondation.
    for (int i = 0; i < 10; i++) {
      for (int j = 0; j < 20; j++) {
        observations.add(router.getId(), flow("10.1.0." + i, "41.82.1.10", 443, 900_000));
      }
    }

    assertThat(collection.detectScans()).isZero();
    assertThat(alertMessages()).isEmpty();
  }

  @Test
  @DisplayName("Un balayage qui continue met l'alerte à jour sans en empiler")
  void acontinuingScanUpdatesTheAlertWithoutStacking() {
    for (int round = 0; round < 2; round++) {
      for (int i = 0; i < 240; i++) {
        observations.add(
            router.getId(), flow("10.9.9.7", "10.4." + (i / 250) + "." + i % 250, 22, 60));
      }
      collection.detectScans();
    }

    assertThat(alertMessages()).hasSize(1);
  }

  @Test
  @DisplayName("Ce qui a été examiné une fois ne l'est pas deux fois")
  void whatHasBeenExaminedOnceIsNotExaminedTwice() {
    for (int i = 0; i < 240; i++) {
      observations.add(
          router.getId(), flow("10.9.9.6", "10.5." + (i / 250) + "." + i % 250, 80, 60));
    }
    collection.detectScans();

    // Les compteurs sont repartis de zéro : la minute suivante est vierge.
    assertThat(collection.detectScans()).isZero();
  }
}

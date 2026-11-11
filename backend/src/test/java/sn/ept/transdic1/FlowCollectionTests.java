package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;

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
import sn.ept.transdic1.flow.service.FlowAccumulator;
import sn.ept.transdic1.flow.service.FlowCollectionService;
import sn.ept.transdic1.flow.service.FlowQueryService;
import sn.ept.transdic1.flow.service.FlowSources;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.EquipmentStatus;
import sn.ept.transdic1.inventory.domain.EquipmentType;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.SiteRepository;

/**
 * Flux réseau sur une vraie base : du comptage en mémoire à ce que l'écran affiche, en passant par
 * le plafonnement du détail et le droit d'exporter.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class FlowCollectionTests {
  @Autowired private FlowAccumulator accumulator;
  @Autowired private FlowCollectionService collection;
  @Autowired private FlowQueryService queries;
  @Autowired private FlowSources sources;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private jakarta.persistence.EntityManager em;

  private Equipment router;
  private String address;

  @BeforeEach
  void data() {
    String unique = UUID.randomUUID().toString();
    Site site = sites.saveAndFlush(new Site("FLX-" + unique.substring(0, 8), "Site flux"));
    address =
        "10.92."
            + (Math.floorMod(unique.hashCode(), 250) + 1)
            + "."
            + (Math.floorMod(unique.substring(9).hashCode(), 250) + 1);
    router =
        equipments.saveAndFlush(
            new Equipment(
                "FLX-EQ-" + unique,
                "Routeur de coeur",
                address,
                EquipmentType.ROUTER,
                EquipmentStatus.UP,
                site));
    em.flush();
    sources.invalidate();
    accumulator.drain();
  }

  private FlowRecord flow(String source, String destination, int port, long bytes) {
    return new FlowRecord(source, destination, 6, 40000, port, bytes, bytes / 100 + 1, 1, 2);
  }

  @Test
  @DisplayName("Seul un équipement inventorié a le droit d'exporter des flux")
  void onlyAnInventoriedEquipmentMayExportFlows() {
    assertThat(sources.equipmentAt(address)).isEqualTo(router.getId());
    assertThat(sources.equipmentAt("203.0.113.200")).isNull();
    // Forme canonique : la même adresse écrite autrement reste la même source.
    assertThat(sources.equipmentAt("::ffff:" + address)).isEqualTo(router.getId());
  }

  @Test
  @DisplayName("Les flux d'une minute sont additionnés par conversation")
  void aMinutesFlowsAreAddedUpPerConversation() {
    accumulator.add(router.getId(), flow("10.1.2.3", "41.82.1.10", 443, 1_000));
    accumulator.add(router.getId(), flow("10.1.2.3", "41.82.1.10", 443, 2_000));
    accumulator.add(router.getId(), flow("10.1.2.4", "41.82.1.10", 22, 500));

    assertThat(collection.flush()).isEqualTo(2);

    Long bytes =
        jdbc.queryForObject(
            "SELECT bytes FROM flow_aggregate WHERE equipment_id = ? AND source_address = ?"
                + " AND destination_port = 443",
            Long.class,
            router.getId(),
            "10.1.2.3");
    assertThat(bytes).isEqualTo(3_000);
    Long total =
        jdbc.queryForObject(
            "SELECT bytes FROM flow_bucket WHERE equipment_id = ?", Long.class, router.getId());
    assertThat(total).isEqualTo(3_500);
  }

  @Test
  @DisplayName("Un flux sans octet ou sans adresse n'est pas compté")
  void aFlowWithoutBytesOrAddressIsNotCounted() {
    accumulator.add(router.getId(), flow("10.1.2.3", "41.82.1.10", 443, 0));
    accumulator.add(router.getId(), new FlowRecord(null, "41.82.1.10", 6, 1, 443, 100, 1, 1, 2));
    accumulator.add(null, flow("10.1.2.3", "41.82.1.10", 443, 100));

    assertThat(collection.flush()).isZero();
  }

  @Test
  @DisplayName("Le détail est plafonné, les totaux restent justes")
  void theDetailIsCappedButTheTotalsStayRight() {
    var small = new FlowAccumulator(1_000);
    for (int i = 0; i < 1_200; i++) {
      small.add(router.getId(), flow("10.1.2." + (i % 250), "41.82.1." + (i / 250), 443, 10));
    }

    var snapshot = small.drain();
    var totals = snapshot.totals().get(router.getId());

    // 1 200 flux comptés dans le total, mais 1 000 conversations au plus dans le détail.
    assertThat(totals.flows()).isEqualTo(1_200);
    assertThat(totals.bytes()).isEqualTo(12_000);
    assertThat(totals.truncated()).isTrue();
    assertThat(snapshot.conversations()).hasSize(1_000);
  }

  @Test
  @DisplayName("Seules les plus grosses conversations sont écrites")
  void onlyTheBiggestConversationsAreWritten() {
    for (int i = 0; i < 400; i++) {
      accumulator.add(router.getId(), flow("10.1.2.3", "41.82.2." + i, 443, 100L + i));
    }

    int written = collection.flush();

    // Plafond de 300 par équipement et par minute (flow.top-conversations).
    assertThat(written).isEqualTo(300);
    Long smallest =
        jdbc.queryForObject(
            "SELECT min(bytes) FROM flow_aggregate WHERE equipment_id = ?",
            Long.class,
            router.getId());
    // Les 100 plus petites (100 à 199 octets) ont été abandonnées.
    assertThat(smallest).isEqualTo(200);
    // Le total, lui, compte les 400.
    Long total =
        jdbc.queryForObject(
            "SELECT bytes FROM flow_bucket WHERE equipment_id = ?", Long.class, router.getId());
    assertThat(total).isEqualTo(400L * 100 + (399L * 400 / 2));
  }

  @Test
  @DisplayName("L'écran montre les totaux, les conversations et les services")
  void theScreenShowsTotalsConversationsAndServices() {
    accumulator.add(router.getId(), flow("10.1.2.3", "41.82.1.10", 443, 5_000));
    accumulator.add(router.getId(), flow("10.1.2.3", "8.8.8.8", 53, 300));
    collection.flush();
    em.flush();

    var overview = queries.overview(3, null);

    assertThat(overview.totals().bytes()).isEqualTo(5_300);
    assertThat(overview.totals().equipments()).isEqualTo(1);
    assertThat(overview.totals().detailTruncated()).isFalse();
    assertThat(overview.equipments())
        .first()
        .satisfies(
            e -> {
              assertThat(e.name()).isEqualTo("Routeur de coeur");
              assertThat(e.bytes()).isEqualTo(5_300);
            });
    assertThat(overview.conversations())
        .first()
        .satisfies(
            c -> {
              assertThat(c.destinationAddress()).isEqualTo("41.82.1.10");
              assertThat(c.service()).isEqualTo("HTTPS");
            });
    assertThat(overview.talkers())
        .first()
        .satisfies(
            t -> {
              assertThat(t.address()).isEqualTo("10.1.2.3");
              assertThat(t.peers()).isEqualTo(2);
            });
    assertThat(overview.services())
        .extracting(FlowQueryService.Service::label)
        .containsExactly("HTTPS", "53/TCP");
    assertThat(overview.timeline()).hasSize(1);
  }

  @Test
  @DisplayName("La purge efface les flux expirés et garde les récents")
  void thePurgeErasesExpiredFlowsAndKeepsRecentOnes() {
    accumulator.add(router.getId(), flow("10.1.2.3", "41.82.1.10", 443, 5_000));
    collection.flush();
    // Un vieux seau, écrit à la main comme s'il datait de trois semaines.
    jdbc.update(
        "INSERT INTO flow_bucket (equipment_id, bucket_start, bytes, packets, flows,"
            + " conversations, truncated) VALUES (?, now() - interval '21 days', 1, 1, 1, 1,"
            + " false)",
        router.getId());
    jdbc.update(
        "INSERT INTO flow_aggregate (equipment_id, bucket_start, source_address,"
            + " destination_address, protocol, destination_port, bytes, packets, flows)"
            + " VALUES (?, now() - interval '21 days', '10.9.9.9', '10.9.9.8', 6, 80, 1, 1, 1)",
        router.getId());
    em.flush();

    assertThat(collection.purgeExpired()).isEqualTo(2);

    Integer remaining =
        jdbc.queryForObject(
            "SELECT count(*) FROM flow_bucket WHERE equipment_id = ?",
            Integer.class,
            router.getId());
    assertThat(remaining).isEqualTo(1);
  }
}

package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import sn.ept.transdic1.collection.service.LldpCollectionService;
import sn.ept.transdic1.collection.service.LldpTable;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.EquipmentStatus;
import sn.ept.transdic1.inventory.domain.EquipmentType;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.dto.TopologyDtos.Neighbour;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.LldpNeighbourRepository;
import sn.ept.transdic1.inventory.repository.SiteRepository;
import sn.ept.transdic1.inventory.service.NeighbourService;

/**
 * Voisins LLDP sur une vraie base : ce qui est enregistré, ce qui est reconnu dans l'inventaire, et
 * ce qu'un administrateur en fait. La topologie ne doit jamais changer toute seule.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class LldpNeighbourTests {
  @Autowired private LldpCollectionService collection;
  @Autowired private NeighbourService neighbours;
  @Autowired private LldpNeighbourRepository repository;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private jakarta.persistence.EntityManager em;

  private Equipment router;
  private Equipment access;
  private static final Instant NOW = Instant.parse("2026-11-12T09:00:00Z");

  @BeforeEach
  void data() {
    String unique = UUID.randomUUID().toString();
    Site site = sites.saveAndFlush(new Site("LLD-" + unique.substring(0, 8), "Site LLDP"));
    router =
        equipments.saveAndFlush(
            equipment("LLD-RT-" + unique, "sw-coeur", "10.94.1.1", EquipmentType.ROUTER, site));
    access =
        equipments.saveAndFlush(
            equipment("LLD-SW-" + unique, "sw-amphi", "10.94.1.2", EquipmentType.SWITCH, site));
    em.flush();
  }

  private Equipment equipment(
      String code, String name, String address, EquipmentType type, Site site) {
    return new Equipment(code, name, address, type, EquipmentStatus.UP, site);
  }

  private LldpTable.Neighbour seen(
      int port, String chassis, String portId, String systemName, String address) {
    return new LldpTable.Neighbour(
        port, "Gi0/" + port, chassis, portId, "Vers " + systemName, systemName, "IOS", address);
  }

  @Test
  @DisplayName("Un voisin reconnu par son adresse de gestion est rattaché à son équipement")
  void aneighbourRecognisedByItsManagementAddressIsAttachedToItsEquipment() {
    collection.update(
        access,
        List.of(seen(1, "00:1a:2b:3c:4d:5e", "Gi0/24", "autre-nom", "10.94.1.1")),
        List.of(),
        NOW);
    em.flush();

    var stored = repository.findByEquipmentId(access.getId());
    assertThat(stored).hasSize(1);
    assertThat(stored.get(0).getMatchedEquipment().getId()).isEqualTo(router.getId());
    assertThat(stored.get(0).getMatchReason()).isEqualTo("ADRESSE");
    assertThat(stored.get(0).getLocalPort()).isEqualTo("Gi0/1");
  }

  @Test
  @DisplayName("À défaut d'adresse, le nom annoncé suffit, domaine et casse mis à part")
  void failingAnAddressTheAnnouncedNameIsEnough() {
    collection.update(
        access,
        List.of(seen(2, "00:1a:2b:3c:4d:5f", "Gi0/2", "SW-Coeur.ept.sn", null)),
        List.of(),
        NOW);
    em.flush();

    var stored = repository.findByEquipmentId(access.getId());
    assertThat(stored.get(0).getMatchedEquipment().getId()).isEqualTo(router.getId());
    assertThat(stored.get(0).getMatchReason()).isEqualTo("NOM");
  }

  @Test
  @DisplayName("Un voisin inconnu de l'inventaire est gardé, sans rattachement")
  void aneighbourUnknownToTheInventoryIsKeptWithoutAttachment() {
    collection.update(
        access,
        List.of(seen(3, "00:aa:bb:cc:dd:ee", "1", "imprimante-labo", "10.94.9.9")),
        List.of(),
        NOW);
    em.flush();

    var stored = repository.findByEquipmentId(access.getId());
    assertThat(stored).hasSize(1);
    assertThat(stored.get(0).getMatchedEquipment()).isNull();
    assertThat(stored.get(0).getRemoteSystemName()).isEqualTo("imprimante-labo");
  }

  @Test
  @DisplayName("Un équipement qui se verrait lui-même n'est pas un voisin utile")
  void anequipmentSeeingItselfIsNotAUsefulNeighbour() {
    collection.update(
        access,
        List.of(seen(4, "00:11:22:33:44:55", "Gi0/4", "sw-amphi", "10.94.1.2")),
        List.of(),
        NOW);
    em.flush();

    assertThat(repository.findByEquipmentId(access.getId()).get(0).getMatchedEquipment()).isNull();
  }

  @Test
  @DisplayName("Un câble débranché fait disparaître la proposition")
  void anunpluggedCableMakesTheProposalDisappear() {
    collection.update(
        access,
        List.of(seen(1, "00:1a:2b:3c:4d:5e", "Gi0/24", "sw-coeur", "10.94.1.1")),
        List.of(),
        NOW);
    em.flush();
    var known = repository.findByEquipmentId(access.getId());

    collection.update(access, List.of(), known, NOW.plusSeconds(3600));
    em.flush();

    assertThat(repository.findByEquipmentId(access.getId())).isEmpty();
  }

  @Test
  @DisplayName("Revoir le même voisin ne crée pas de doublon, même à un autre repère de temps")
  void seeingTheSameNeighbourAgainDoesNotCreateADuplicate() {
    var first = List.of(seen(1, "00:1a:2b:3c:4d:5e", "Gi0/24", "sw-coeur", "10.94.1.1"));
    collection.update(access, first, List.of(), NOW);
    em.flush();
    var known = repository.findByEquipmentId(access.getId());

    collection.update(access, first, known, NOW.plusSeconds(3600));
    em.flush();

    var stored = repository.findByEquipmentId(access.getId());
    assertThat(stored).hasSize(1);
    assertThat(stored.get(0).getLastSeenAt()).isEqualTo(NOW.plusSeconds(3600));
  }

  @Test
  @DisplayName("L'écran propose le routeur comme amont, et dit comment le voisin a été reconnu")
  void thescreenSuggestsTheRouterAsUpstream() {
    collection.update(
        access,
        List.of(seen(1, "00:1a:2b:3c:4d:5e", "Gi0/24", "sw-coeur", "10.94.1.1")),
        List.of(),
        NOW);
    em.flush();

    List<Neighbour> discovered =
        neighbours.discovered().stream()
            .filter(n -> n.equipmentId().equals(access.getId()))
            .toList();

    assertThat(discovered).hasSize(1);
    Neighbour neighbour = discovered.get(0);
    assertThat(neighbour.matchedEquipmentName()).isEqualTo("sw-coeur");
    assertThat(neighbour.matchReason()).isEqualTo("ADRESSE");
    // Un commutateur dépend d'un routeur, pas l'inverse.
    assertThat(neighbour.suggestRemoteUpstream()).isTrue();
    assertThat(neighbour.alreadyLinked()).isFalse();
  }

  @Test
  @DisplayName("Accepter crée le lien, et l'écran le dit la fois suivante")
  void acceptingCreatesTheLink() {
    collection.update(
        access,
        List.of(seen(1, "00:1a:2b:3c:4d:5e", "Gi0/24", "sw-coeur", "10.94.1.1")),
        List.of(),
        NOW);
    em.flush();
    UUID id = repository.findByEquipmentId(access.getId()).get(0).getId();

    neighbours.accept(id, true);
    em.flush();

    Integer links =
        jdbc.queryForObject(
            "SELECT count(*) FROM equipment_link WHERE equipment_id = ? AND upstream_id = ?",
            Integer.class,
            access.getId(),
            router.getId());
    assertThat(links).isEqualTo(1);
    assertThat(
            neighbours.discovered().stream()
                .filter(n -> n.id().equals(id))
                .findFirst()
                .orElseThrow()
                .alreadyLinked())
        .isTrue();
  }

  @Test
  @DisplayName("Le sens peut être retourné à l'acceptation")
  void thedirectionCanBeFlippedOnAcceptance() {
    collection.update(
        access,
        List.of(seen(1, "00:1a:2b:3c:4d:5e", "Gi0/24", "sw-coeur", "10.94.1.1")),
        List.of(),
        NOW);
    em.flush();
    UUID id = repository.findByEquipmentId(access.getId()).get(0).getId();

    neighbours.accept(id, false);
    em.flush();

    Integer links =
        jdbc.queryForObject(
            "SELECT count(*) FROM equipment_link WHERE equipment_id = ? AND upstream_id = ?",
            Integer.class,
            router.getId(),
            access.getId());
    assertThat(links).isEqualTo(1);
  }

  @Test
  @DisplayName("Un voisin non reconnu ne peut pas être accepté")
  void anunrecognisedNeighbourCannotBeAccepted() {
    collection.update(
        access,
        List.of(seen(3, "00:aa:bb:cc:dd:ee", "1", "imprimante-labo", null)),
        List.of(),
        NOW);
    em.flush();
    UUID id = repository.findByEquipmentId(access.getId()).get(0).getId();

    assertThatThrownBy(() -> neighbours.accept(id, true))
        .hasMessageContaining("aucun équipement de l'inventaire");
  }

  @Test
  @DisplayName("Un voisin écarté ne revient plus")
  void anignoredNeighbourDoesNotComeBack() {
    collection.update(
        access,
        List.of(seen(1, "00:1a:2b:3c:4d:5e", "Gi0/24", "sw-coeur", "10.94.1.1")),
        List.of(),
        NOW);
    em.flush();
    UUID id = repository.findByEquipmentId(access.getId()).get(0).getId();

    neighbours.ignore(id);
    em.flush();

    assertThat(neighbours.discovered().stream().map(Neighbour::id)).doesNotContain(id);
    // La ligne reste : le voisin existe toujours, c'est la proposition qui est écartée.
    assertThat(repository.findById(id)).isPresent();
  }
}

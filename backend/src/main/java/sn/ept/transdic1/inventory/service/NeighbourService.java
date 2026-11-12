package sn.ept.transdic1.inventory.service;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import sn.ept.transdic1.audit.service.AuditService;
import sn.ept.transdic1.common.scope.SiteScope;
import sn.ept.transdic1.inventory.domain.EquipmentType;
import sn.ept.transdic1.inventory.domain.LldpNeighbour;
import sn.ept.transdic1.inventory.dto.TopologyDtos.Neighbour;
import sn.ept.transdic1.inventory.repository.LldpNeighbourRepository;

/**
 * Liens de topologie proposés par LLDP (ADR 0034) : ce que les équipements disent voir au bout de
 * leurs ports, et ce qu'un administrateur en fait.
 *
 * <p>La plateforme ne modifie jamais la topologie d'elle-même. Elle propose, en disant d'où vient
 * l'information ; accepter crée un lien ordinaire, écarter fait taire la proposition.
 */
@Service
public class NeighbourService {
  /**
   * Qui est en amont de qui, quand les deux bouts sont reconnus. LLDP ne le dit pas : il dit
   * seulement que deux machines sont voisines. On propose donc un sens d'après le type — un poste
   * dépend d'un commutateur, un commutateur d'un routeur — et l'administrateur le retourne d'un
   * clic si la réalité est autre.
   */
  private static final List<EquipmentType> UPSTREAM_FIRST =
      List.of(
          EquipmentType.ROUTER,
          EquipmentType.SWITCH,
          EquipmentType.WIFI_ACCESS_POINT,
          EquipmentType.SERVER,
          EquipmentType.VIRTUAL_MACHINE);

  private final LldpNeighbourRepository neighbours;
  private final TopologyService topology;
  private final AuditService audit;
  private final SiteScope scope;

  public NeighbourService(
      LldpNeighbourRepository neighbours,
      TopologyService topology,
      AuditService audit,
      SiteScope scope) {
    this.neighbours = neighbours;
    this.topology = topology;
    this.audit = audit;
    this.scope = scope;
  }

  /** Voisins découverts, dans le périmètre de sites du compte, les reconnus d'abord. */
  @Transactional(readOnly = true)
  public List<Neighbour> discovered() {
    return neighbours.findByIgnoredFalse().stream()
        .filter(neighbour -> scope.allows(siteOf(neighbour)))
        .map(this::toDto)
        .sorted(
            java.util.Comparator.comparing(Neighbour::matchedEquipmentId, nullsLast())
                .thenComparing(Neighbour::equipmentName)
                .thenComparing(Neighbour::localPort, nullsLast()))
        .toList();
  }

  private static <T extends Comparable<T>> java.util.Comparator<T> nullsLast() {
    return java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder());
  }

  /**
   * Crée le lien correspondant à un voisin reconnu.
   *
   * @param remoteUpstream vrai si c'est le voisin qui est en amont, faux si c'est l'équipement
   *     local
   */
  @Transactional
  public void accept(UUID neighbourId, boolean remoteUpstream) {
    LldpNeighbour neighbour = require(neighbourId);
    if (neighbour.getMatchedEquipment() == null) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Ce voisin ne correspond à aucun équipement de l'inventaire : ajoutez-le d'abord");
    }
    UUID local = neighbour.getEquipment().getId();
    UUID remote = neighbour.getMatchedEquipment().getId();
    if (remoteUpstream) {
      topology.addLink(local, remote);
    } else {
      topology.addLink(remote, local);
    }
    audit.record(
        "TOPOLOGY_LINK_FROM_LLDP",
        "EQUIPMENT",
        local,
        "voisin=" + remote + ", port=" + describePort(neighbour));
  }

  /** Écarte une proposition : elle ne sera plus affichée, même si le voisin reste visible. */
  @Transactional
  public void ignore(UUID neighbourId) {
    LldpNeighbour neighbour = require(neighbourId);
    neighbour.setIgnored(true);
    neighbours.save(neighbour);
    audit.record(
        "TOPOLOGY_NEIGHBOUR_IGNORED",
        "EQUIPMENT",
        neighbour.getEquipment().getId(),
        "port=" + describePort(neighbour));
  }

  private LldpNeighbour require(UUID id) {
    LldpNeighbour neighbour =
        neighbours
            .findById(id)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Voisin introuvable"));
    if (!scope.allows(siteOf(neighbour))) {
      // Hors périmètre : même réponse que s'il n'existait pas.
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Voisin introuvable");
    }
    return neighbour;
  }

  private UUID siteOf(LldpNeighbour neighbour) {
    return neighbour.getEquipment().getSite() == null
        ? null
        : neighbour.getEquipment().getSite().getId();
  }

  private String describePort(LldpNeighbour neighbour) {
    return neighbour.getLocalPort() == null
        ? String.valueOf(neighbour.getLocalPortIndex())
        : neighbour.getLocalPort();
  }

  private Neighbour toDto(LldpNeighbour neighbour) {
    var matched = neighbour.getMatchedEquipment();
    boolean remoteUpstream = suggestsRemoteUpstream(neighbour);
    return new Neighbour(
        neighbour.getId(),
        neighbour.getEquipment().getId(),
        neighbour.getEquipment().getName(),
        describePort(neighbour),
        neighbour.getRemoteSystemName(),
        neighbour.getRemotePortDescription() == null
            ? neighbour.getRemotePortId()
            : neighbour.getRemotePortDescription(),
        neighbour.getRemoteManagementAddress(),
        neighbour.getRemoteChassisId(),
        matched == null ? null : matched.getId(),
        matched == null ? null : matched.getName(),
        neighbour.getMatchReason(),
        remoteUpstream,
        matched != null && topology.hasLink(neighbour.getEquipment().getId(), matched.getId()),
        neighbour.getLastSeenAt());
  }

  /** Sens proposé : le type le plus « cœur de réseau » est tenu pour l'amont. */
  private boolean suggestsRemoteUpstream(LldpNeighbour neighbour) {
    if (neighbour.getMatchedEquipment() == null) {
      return true;
    }
    int local = rank(neighbour.getEquipment().getType());
    int remote = rank(neighbour.getMatchedEquipment().getType());
    return remote <= local;
  }

  private int rank(EquipmentType type) {
    int index = UPSTREAM_FIRST.indexOf(type);
    return index < 0 ? UPSTREAM_FIRST.size() : index;
  }
}

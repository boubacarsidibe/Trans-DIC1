package sn.ept.transdic1.inventory.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/** Topologie du réseau : équipements (nœuds) et dépendances vers l'amont (liens). */
public final class TopologyDtos {
  private TopologyDtos() {}

  public record Node(
      UUID id,
      String name,
      String inventoryCode,
      String type,
      String status,
      UUID siteId,
      String siteName) {}

  /** {@code equipmentId} dépend de {@code upstreamId}. */
  public record Link(UUID id, UUID equipmentId, UUID upstreamId) {}

  public record Graph(List<Node> nodes, List<Link> links) {}

  public record LinkRequest(@NotNull UUID equipmentId, @NotNull UUID upstreamId) {}

  /**
   * Un voisin annoncé par LLDP (ADR 0034) : ce qu'un équipement dit voir au bout d'un de ses ports,
   * et ce que la plateforme a su en reconnaître.
   *
   * @param matchedEquipmentId l'équipement de l'inventaire reconnu, ou nul s'il n'y en a pas
   * @param matchReason comment il a été reconnu : par son adresse de gestion ou par son nom
   * @param suggestRemoteUpstream sens proposé pour le lien, que l'administrateur peut retourner
   * @param alreadyLinked vrai si un lien existe déjà entre les deux, dans un sens ou dans l'autre
   */
  public record Neighbour(
      UUID id,
      UUID equipmentId,
      String equipmentName,
      String localPort,
      String remoteSystemName,
      String remotePort,
      String remoteManagementAddress,
      String remoteChassisId,
      UUID matchedEquipmentId,
      String matchedEquipmentName,
      String matchReason,
      boolean suggestRemoteUpstream,
      boolean alreadyLinked,
      java.time.Instant lastSeenAt) {}

  /** Sens choisi à l'acceptation d'un voisin. */
  public record NeighbourAcceptance(boolean remoteUpstream) {}

  /**
   * Équipements rendus injoignables par la panne de {@code equipmentId}, du plus proche au plus
   * loin.
   */
  public record Impact(UUID equipmentId, List<Node> dependents) {}
}

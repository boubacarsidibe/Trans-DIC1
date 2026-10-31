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
   * Équipements rendus injoignables par la panne de {@code equipmentId}, du plus proche au plus
   * loin.
   */
  public record Impact(UUID equipmentId, List<Node> dependents) {}
}

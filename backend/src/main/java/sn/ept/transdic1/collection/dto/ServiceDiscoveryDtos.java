package sn.ept.transdic1.collection.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Services découverts par l'agent et services suivis à la demande (ADR 0056). */
public final class ServiceDiscoveryDtos {
  private ServiceDiscoveryDtos() {}

  /** Un service système actif sur la machine, suivi ou non. */
  public record DiscoveredService(String nom, boolean suivi, UUID serviceId) {}

  /** Un port TCP en écoute. */
  public record ListeningPort(String protocole, String adresse, int port) {}

  /**
   * Ce que l'agent a vu à son dernier instantané, moins la liste d'exclusion ; plus les services
   * suivis que l'agent ne voit plus actifs (arrêtés : c'est justement ce qu'il faut voir).
   */
  public record DiscoveredServices(
      Instant recuLe, List<DiscoveredService> services, List<ListeningPort> ports, int exclus) {}

  /** Suivre un service système : même règle de nom que l'agent. */
  public record FollowRequest(@NotBlank @Pattern(regexp = "[A-Za-z0-9_.@-]{1,64}") String nom) {}

  /** Liste envoyée à l'agent : les services dont il doit remonter l'état. */
  public record FollowedNames(List<String> services) {}
}

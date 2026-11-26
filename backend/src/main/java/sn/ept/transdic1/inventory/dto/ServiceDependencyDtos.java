package sn.ept.transdic1.inventory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import sn.ept.transdic1.inventory.domain.ServiceType;

/** Contrats des dépendances entre services (ADR 0056). */
public final class ServiceDependencyDtos {
  private ServiceDependencyDtos() {}

  /** Un service (sonde ou service suivi par l'agent) et l'équipement sur lequel il tourne. */
  public record ServiceNode(
      UUID id,
      String nom,
      ServiceType type,
      UUID equipementId,
      String equipementNom,
      String equipementCode,
      String site) {}

  /** « service » dépend de « dependDe ». */
  public record DependencyResponse(
      UUID id, ServiceNode service, ServiceNode dependDe, String creePar, Instant creeLe) {}

  public record DependencyRequest(@NotNull UUID serviceId, @NotNull UUID dependDeId) {}

  /**
   * Import CSV, une dépendance par ligne : {@code code_equipement;service;code_equipement;service}
   * (le premier service dépend du second). En-tête facultatif.
   */
  public record ImportRequest(@NotBlank @Size(max = 200_000) String csv) {}

  /** Bilan d'un import : ce qui a été créé, et pourquoi chaque ligne écartée l'a été. */
  public record ImportResult(int creees, int dejaPresentes, List<String> erreurs) {}
}

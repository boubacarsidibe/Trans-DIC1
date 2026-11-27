package sn.ept.transdic1.threat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import sn.ept.transdic1.threat.domain.EndpointFindingState;

/** Contrats de l'écran de l'EDR léger (ADR 0058). */
public final class EndpointDtos {
  private EndpointDtos() {}

  /** Bilan rendu à l'agent après un rapport. */
  public record ReportResult(
      @Schema(description = "Vrai pour le premier rapport, qui fixe la ligne de base")
          boolean baseline,
      @Schema(description = "Constats nouvellement ouverts") int findings) {}

  /** Un constat, tel que l'écran l'affiche. */
  public record FindingResponse(
      UUID id,
      UUID equipementId,
      String equipementNom,
      String regle,
      String techniqueId,
      String techniqueNom,
      String tactique,
      String techniqueUrl,
      String gravite,
      String sujet,
      String detail,
      EndpointFindingState etat,
      Instant detecteLe,
      Instant vuLe,
      String decidePar,
      Instant decideLe) {}

  /** Un poste qui envoie sa télémétrie de sécurité. */
  public record ProfileResponse(
      UUID equipementId,
      String equipementNom,
      String systeme,
      Instant ligneDeBaseLe,
      Instant dernierRapport,
      int rapports,
      boolean partiel,
      int constatsOuverts) {}

  /** Vue d'ensemble : les postes surveillés et les constats récents. */
  public record Overview(List<ProfileResponse> postes, List<FindingResponse> constats) {}

  /** Jugement d'un constat par un opérateur. */
  public record DecisionRequest(
      @Schema(description = "LEGITIMATE (changement voulu) ou MALICIOUS (attaque confirmée)")
          @NotNull
          EndpointFindingState etat) {}
}

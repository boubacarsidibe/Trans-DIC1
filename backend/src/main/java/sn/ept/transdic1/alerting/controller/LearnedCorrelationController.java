package sn.ept.transdic1.alerting.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import sn.ept.transdic1.alerting.dto.LearnedCorrelationDtos.LearnedCorrelationResponse;
import sn.ept.transdic1.alerting.service.LearnedCorrelationService;

@Tag(
    name = "Corrélations apprises",
    description =
        "Liens entre équipements déduits de l'historique des alertes (M9, ADR 0042)."
            + " Consultation pour tous les rôles dans la limite du périmètre d'accès ;"
            + " acceptation et rejet réservés aux administrateurs et audités."
            + " Une proposition ne sert à rien avant d'être acceptée.")
@RestController
@RequestMapping("/api/v1/correlations-apprises")
public class LearnedCorrelationController {

  private final LearnedCorrelationService service;

  public LearnedCorrelationController(LearnedCorrelationService service) {
    this.service = service;
  }

  @Operation(
      summary = "Lister les liens proposés",
      description =
          "Les plus confiants d'abord. Un lien n'est listé que si les deux équipements sont"
              + " visibles : une proposition porte sur leur couple, en montrer la moitié n'aurait"
              + " aucun sens.")
  @GetMapping
  public List<LearnedCorrelationResponse> list() {
    return service.proposed().stream()
        .map(
            proposal ->
                new LearnedCorrelationResponse(
                    proposal.id(),
                    proposal.firstEquipmentId(),
                    proposal.firstEquipmentName(),
                    proposal.secondEquipmentId(),
                    proposal.secondEquipmentName(),
                    proposal.together(),
                    proposal.firstWindows(),
                    proposal.secondWindows(),
                    proposal.totalWindows(),
                    proposal.support(),
                    proposal.confidence(),
                    proposal.firstSeenAt()))
        .toList();
  }

  @Operation(
      summary = "Accepter un lien proposé",
      description = "Le lien rapproche désormais les alertes des deux équipements en incidents.")
  @ApiResponse(responseCode = "404", description = "Proposition introuvable", content = @Content)
  @ApiResponse(responseCode = "409", description = "Déjà tranchée", content = @Content)
  @PostMapping("/{id}/acceptation")
  @PreAuthorize("hasRole('ADMIN')")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void accept(@PathVariable UUID id, Authentication authentication) {
    service.accept(id, authentication.getName());
  }

  @Operation(
      summary = "Écarter un lien proposé",
      description = "Il ne sera plus reproposé, même si la statistique persiste.")
  @ApiResponse(responseCode = "404", description = "Proposition introuvable", content = @Content)
  @ApiResponse(responseCode = "409", description = "Déjà tranchée", content = @Content)
  @PostMapping("/{id}/rejet")
  @PreAuthorize("hasRole('ADMIN')")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void reject(@PathVariable UUID id, Authentication authentication) {
    service.reject(id, authentication.getName());
  }
}

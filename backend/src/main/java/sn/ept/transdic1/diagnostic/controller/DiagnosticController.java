package sn.ept.transdic1.diagnostic.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import sn.ept.transdic1.diagnostic.dto.CauseConfirmationRequest;
import sn.ept.transdic1.diagnostic.dto.CauseConfirmationResponse;
import sn.ept.transdic1.diagnostic.dto.DiagnosticResponse;
import sn.ept.transdic1.diagnostic.service.CauseConfirmationService;
import sn.ept.transdic1.diagnostic.service.DiagnosticService;

@Tag(name = "Alertes")
@RestController
@RequestMapping("/api/v1/alertes")
public class DiagnosticController {
  private final DiagnosticService service;
  private final CauseConfirmationService confirmations;

  public DiagnosticController(DiagnosticService service, CauseConfirmationService confirmations) {
    this.service = service;
    this.confirmations = confirmations;
  }

  @Operation(
      summary = "Diagnostiquer une alerte",
      description =
          "Calculé à la demande, de façon déterministe (ADR 0024) : résumé, impact, causes"
              + " probables classées avec leurs preuves, alertes voisines, événements et"
              + " changements récents, contexte des mesures, incidents similaires et pistes."
              + " Tous les rôles ; même périmètre par site que la lecture de l'alerte.")
  @ApiResponse(responseCode = "200", description = "Diagnostic calculé")
  @ApiResponse(responseCode = "404", description = "Alerte introuvable", content = @Content)
  @GetMapping("/{id}/diagnostic")
  public DiagnosticResponse diagnostic(@PathVariable UUID id) {
    return service.diagnose(id);
  }

  @Operation(
      summary = "Confirmer la vraie cause d'une alerte",
      description =
          "L'opérateur dit laquelle des causes proposées était la bonne, ou qu'aucune ne"
              + " convenait (M12, ADR 0045). Toutes les propositions sont enregistrées avec leur"
              + " verdict, parce que ce sont les refus qui disent si une source a vraiment raison."
              + " Au-delà de vingt confirmations, les poids du classement des causes viennent de"
              + " ces réponses plutôt que de valeurs fixées à la main ; en dessous, ils sont"
              + " seulement tirés vers elles. Reconfirmer remplace l'avis précédent."
              + " Opérateurs NOC et administrateurs.")
  @ApiResponse(responseCode = "200", description = "Verdict enregistré")
  @ApiResponse(responseCode = "404", description = "Alerte introuvable", content = @Content)
  @ApiResponse(
      responseCode = "409",
      description = "Aucune cause proposée, ou cause absente des propositions",
      content = @Content)
  @PostMapping("/{id}/cause-confirmee")
  @PreAuthorize("hasAnyRole('ADMIN','NOC_OPERATOR')")
  public CauseConfirmationResponse confirmCause(
      @PathVariable UUID id,
      @Valid @RequestBody(required = false) CauseConfirmationRequest request,
      Authentication authentication) {
    var outcome =
        confirmations.confirm(
            id, request == null ? null : request.source(), authentication.getName());
    return new CauseConfirmationResponse(
        outcome.recorded(), outcome.chosen(), outcome.confirmations());
  }
}

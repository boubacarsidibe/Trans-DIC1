package sn.ept.transdic1.alerting.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import sn.ept.transdic1.alerting.dto.AlertLevelDtos.EscalationRequest;
import sn.ept.transdic1.alerting.dto.AlertLevelDtos.EscalationTarget;
import sn.ept.transdic1.alerting.dto.AlertLevelDtos.NoteRequest;
import sn.ept.transdic1.alerting.dto.AlertLevelDtos.SendBackRequest;
import sn.ept.transdic1.alerting.dto.AlertResponse;
import sn.ept.transdic1.alerting.service.AlertLevelService;

@Tag(
    name = "Escalade",
    description =
        "Journal d'intervention, escalade au niveau supérieur et renvoi au niveau inférieur (ADR"
            + " 0059). Opérateurs NOC et administrateurs ; un opérateur n'agit que sur les alertes"
            + " de son niveau ou d'un niveau inférieur.")
@RestController
@RequestMapping("/api/v1/alertes")
public class AlertLevelController {
  private final AlertLevelService service;

  public AlertLevelController(AlertLevelService service) {
    this.service = service;
  }

  @Operation(summary = "Ajouter une note au journal d'intervention")
  @PostMapping("/{id}/notes")
  @PreAuthorize("hasAnyRole('ADMIN','NOC_OPERATOR')")
  @ResponseStatus(HttpStatus.CREATED)
  public AlertResponse note(
      @PathVariable UUID id, @Valid @RequestBody NoteRequest request, Authentication auth) {
    return service.note(id, auth.getName(), request.text());
  }

  @Operation(
      summary = "Niveau visé par une escalade et destinataires possibles",
      description = "Niveau supérieur occupé suivant ; vide quand ce sont les administrateurs.")
  @GetMapping("/{id}/escalade")
  @PreAuthorize("hasAnyRole('ADMIN','NOC_OPERATOR')")
  public EscalationTarget target(@PathVariable UUID id, Authentication auth) {
    return service.target(id, auth.getName());
  }

  @Operation(summary = "Escalader au niveau supérieur")
  @ApiResponse(
      responseCode = "400",
      description = "Case ou destinataire invalide",
      content = @Content)
  @ApiResponse(
      responseCode = "403",
      description = "Alerte d'un niveau supérieur",
      content = @Content)
  @ApiResponse(
      responseCode = "409",
      description = "Alerte fermée, déjà au plus haut ou changée de niveau entre-temps",
      content = @Content)
  @PostMapping("/{id}/escalade")
  @PreAuthorize("hasAnyRole('ADMIN','NOC_OPERATOR')")
  public AlertResponse escalate(
      @PathVariable UUID id, @Valid @RequestBody EscalationRequest request, Authentication auth) {
    return service.escalate(id, auth.getName(), request);
  }

  @Operation(summary = "Renvoyer au niveau inférieur, avec une consigne")
  @ApiResponse(
      responseCode = "409",
      description = "Aucun niveau inférieur occupé",
      content = @Content)
  @PostMapping("/{id}/renvoi")
  @PreAuthorize("hasAnyRole('ADMIN','NOC_OPERATOR')")
  public AlertResponse sendBack(
      @PathVariable UUID id, @Valid @RequestBody SendBackRequest request, Authentication auth) {
    return service.sendBack(id, auth.getName(), request);
  }
}

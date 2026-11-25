package sn.ept.transdic1.remediation.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import sn.ept.transdic1.remediation.dto.RemediationDtos.AlertRemediation;
import sn.ept.transdic1.remediation.dto.RemediationDtos.AvailableAction;
import sn.ept.transdic1.remediation.dto.RemediationDtos.JobResponse;
import sn.ept.transdic1.remediation.dto.RemediationDtos.LaunchRequest;
import sn.ept.transdic1.remediation.dto.RemediationDtos.PolicyRequest;
import sn.ept.transdic1.remediation.dto.RemediationDtos.PolicyResponse;
import sn.ept.transdic1.remediation.service.RemediationService;

@Tag(
    name = "Actions correctives",
    description =
        "Remédiation automatique (ADR 0050) : une règle d'alerte peut demander à l'agent de"
            + " l'équipement d'exécuter une action qu'il a lui-même déclarée (par son nom).")
@RestController
@RequestMapping("/api/v1")
public class RemediationController {
  private final RemediationService service;

  public RemediationController(RemediationService service) {
    this.service = service;
  }

  @Operation(
      summary = "Actions annoncées par les agents",
      description =
          "Noms et descriptions des actions que les agents actifs savent exécuter, avec le nombre"
              + " d'équipements qui les offrent. Opérateurs NOC et administrateurs.")
  @GetMapping("/actions-correctives/disponibles")
  @PreAuthorize("hasAnyRole('ADMIN','NOC_OPERATOR')")
  public List<AvailableAction> available() {
    return service.available();
  }

  @Operation(
      summary = "Action corrective d'une règle",
      description = "204 si la règle n'en a pas. Opérateurs NOC et administrateurs.")
  @ApiResponse(responseCode = "200", description = "Action configurée")
  @ApiResponse(
      responseCode = "204",
      description = "Aucune action sur cette règle",
      content = @Content)
  @ApiResponse(responseCode = "404", description = "Règle introuvable", content = @Content)
  @GetMapping("/regles-alertes/{ruleId}/action-corrective")
  @PreAuthorize("hasAnyRole('ADMIN','NOC_OPERATOR')")
  public ResponseEntity<PolicyResponse> policy(@PathVariable UUID ruleId) {
    return service
        .policy(ruleId)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.noContent().build());
  }

  @Operation(
      summary = "Définir l'action corrective d'une règle",
      description =
          "mode=AUTO : l'action part dès l'ouverture de l'alerte ; APPROVAL : elle attend qu'un"
              + " opérateur l'approuve. Le délai de grâce (cooldownMinutes, 30 par défaut)"
              + " empêche de relancer l'action en boucle sur une alerte qui clignote."
              + " Administrateurs.")
  @ApiResponse(responseCode = "404", description = "Règle introuvable", content = @Content)
  @PutMapping("/regles-alertes/{ruleId}/action-corrective")
  @PreAuthorize("hasRole('ADMIN')")
  public PolicyResponse savePolicy(
      @PathVariable UUID ruleId,
      @Valid @RequestBody PolicyRequest request,
      Authentication authentication) {
    return service.savePolicy(ruleId, request, authentication.getName());
  }

  @Operation(summary = "Retirer l'action corrective d'une règle", description = "Administrateurs.")
  @ApiResponse(
      responseCode = "404",
      description = "Règle ou action introuvable",
      content = @Content)
  @DeleteMapping("/regles-alertes/{ruleId}/action-corrective")
  @PreAuthorize("hasRole('ADMIN')")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deletePolicy(@PathVariable UUID ruleId) {
    service.deletePolicy(ruleId);
  }

  @Operation(
      summary = "Actions correctives d'une alerte",
      description =
          "Actions que l'agent de l'équipement sait exécuter, action prévue par la règle, et"
              + " exécutions déjà demandées avec leur résultat. Tous les rôles ; même périmètre"
              + " que l'alerte.")
  @ApiResponse(responseCode = "404", description = "Alerte introuvable", content = @Content)
  @GetMapping("/alertes/{alertId}/actions-correctives")
  public AlertRemediation forAlert(@PathVariable UUID alertId) {
    return service.forAlert(alertId);
  }

  @Operation(
      summary = "Lancer une action corrective",
      description =
          "L'agent la prendra à son prochain passage (au plus un intervalle de collecte). Opérateurs"
              + " NOC et administrateurs.")
  @ApiResponse(responseCode = "201", description = "Action mise en file")
  @ApiResponse(responseCode = "404", description = "Alerte introuvable", content = @Content)
  @ApiResponse(
      responseCode = "409",
      description = "Alerte fermée, pas d'agent, action inconnue de l'agent, ou déjà en cours",
      content = @Content)
  @PostMapping("/alertes/{alertId}/actions-correctives")
  @PreAuthorize("hasAnyRole('ADMIN','NOC_OPERATOR')")
  @ResponseStatus(HttpStatus.CREATED)
  public JobResponse launch(
      @PathVariable UUID alertId,
      @Valid @RequestBody LaunchRequest request,
      Authentication authentication) {
    return service.launch(alertId, request.actionName(), authentication.getName());
  }

  @Operation(
      summary = "Approuver une action corrective",
      description = "Opérateurs NOC et administrateurs.")
  @ApiResponse(responseCode = "404", description = "Exécution introuvable", content = @Content)
  @ApiResponse(responseCode = "409", description = "N'attend pas d'approbation", content = @Content)
  @PostMapping("/actions-correctives/{jobId}/approbation")
  @PreAuthorize("hasAnyRole('ADMIN','NOC_OPERATOR')")
  public JobResponse approve(@PathVariable UUID jobId, Authentication authentication) {
    return service.approve(jobId, authentication.getName());
  }

  @Operation(
      summary = "Refuser une action corrective",
      description = "Opérateurs NOC et administrateurs.")
  @ApiResponse(responseCode = "404", description = "Exécution introuvable", content = @Content)
  @ApiResponse(responseCode = "409", description = "N'attend pas d'approbation", content = @Content)
  @PostMapping("/actions-correctives/{jobId}/refus")
  @PreAuthorize("hasAnyRole('ADMIN','NOC_OPERATOR')")
  public JobResponse reject(@PathVariable UUID jobId, Authentication authentication) {
    return service.reject(jobId, authentication.getName());
  }
}

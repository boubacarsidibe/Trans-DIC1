package sn.ept.transdic1.alerting.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import sn.ept.transdic1.alerting.domain.AlertState;
import sn.ept.transdic1.alerting.dto.AlertDetailResponse;
import sn.ept.transdic1.alerting.dto.AlertResponse;
import sn.ept.transdic1.alerting.dto.AssignmentRequest;
import sn.ept.transdic1.alerting.dto.CommentRequest;
import sn.ept.transdic1.alerting.service.AlertWorkflowService;

@Tag(
    name = "Alertes",
    description =
        "Consultation pour tous les rôles ; prise en charge et résolution pour NOC_OPERATOR et ADMIN ;"
            + " assignation, archivage et suppression pour ADMIN.")
@RestController
@RequestMapping("/api/v1/alertes")
public class AlertController {
  private static final String CONFLICT = "Transition impossible depuis l'état actuel de l'alerte";

  private final AlertWorkflowService service;

  public AlertController(AlertWorkflowService service) {
    this.service = service;
  }

  @Operation(
      summary = "Lister les alertes",
      description =
          "Liste paginée (20 par page, plus récentes d'abord), filtrable par état et par équipement."
              + " Les alertes supprimées sont masquées.")
  @GetMapping
  public Page<AlertResponse> list(
      @RequestParam(required = false) AlertState state,
      @RequestParam(required = false) UUID equipmentId,
      @PageableDefault(
              size = 20,
              sort = "triggeredAt",
              direction = org.springframework.data.domain.Sort.Direction.DESC)
          Pageable pageable) {
    return service.list(state, equipmentId, pageable);
  }

  @Operation(summary = "Consulter une alerte et son historique")
  @ApiResponse(responseCode = "404", description = "Alerte introuvable", content = @Content)
  @GetMapping("/{id}")
  public AlertDetailResponse get(@PathVariable UUID id) {
    return service.get(id);
  }

  @Operation(summary = "Prendre en charge une alerte ouverte")
  @ApiResponse(responseCode = "409", description = CONFLICT, content = @Content)
  @PostMapping("/{id}/prise-en-charge")
  @PreAuthorize("hasAnyRole('ADMIN','NOC_OPERATOR')")
  public AlertResponse take(
      @PathVariable UUID id,
      @Valid @RequestBody(required = false) CommentRequest request,
      Authentication authentication) {
    return service.take(id, authentication.getName(), comment(request));
  }

  @Operation(summary = "Assigner ou réassigner une alerte à un opérateur")
  @ApiResponse(responseCode = "400", description = "Assigné invalide", content = @Content)
  @ApiResponse(responseCode = "409", description = CONFLICT, content = @Content)
  @PostMapping("/{id}/assignation")
  @PreAuthorize("hasRole('ADMIN')")
  public AlertResponse assign(
      @PathVariable UUID id,
      @Valid @RequestBody AssignmentRequest request,
      Authentication authentication) {
    return service.assign(id, request.userId(), authentication.getName(), request.comment());
  }

  @Operation(
      summary = "Ajouter un opérateur au suivi",
      description = "Assignation multiple : les opérateurs déjà assignés restent assignés.")
  @ApiResponse(responseCode = "400", description = "Assigné invalide", content = @Content)
  @ApiResponse(responseCode = "409", description = CONFLICT, content = @Content)
  @PostMapping("/{id}/assignes")
  @PreAuthorize("hasRole('ADMIN')")
  public AlertResponse addAssignee(
      @PathVariable UUID id,
      @Valid @RequestBody AssignmentRequest request,
      Authentication authentication) {
    return service.addAssignee(id, request.userId(), authentication.getName(), request.comment());
  }

  @Operation(summary = "Retirer un opérateur du suivi")
  @ApiResponse(responseCode = "404", description = "Opérateur non assigné", content = @Content)
  @DeleteMapping("/{id}/assignes/{userId}")
  @PreAuthorize("hasRole('ADMIN')")
  public AlertResponse removeAssignee(
      @PathVariable UUID id, @PathVariable UUID userId, Authentication authentication) {
    return service.removeAssignee(id, userId, authentication.getName());
  }

  @Operation(summary = "Résoudre manuellement une alerte")
  @ApiResponse(responseCode = "409", description = CONFLICT, content = @Content)
  @PostMapping("/{id}/resolution")
  @PreAuthorize("hasAnyRole('ADMIN','NOC_OPERATOR')")
  public AlertResponse resolve(
      @PathVariable UUID id,
      @Valid @RequestBody(required = false) CommentRequest request,
      Authentication authentication) {
    return service.resolve(id, authentication.getName(), comment(request));
  }

  @Operation(summary = "Archiver une alerte résolue")
  @ApiResponse(responseCode = "409", description = CONFLICT, content = @Content)
  @PostMapping("/{id}/archivage")
  @PreAuthorize("hasRole('ADMIN')")
  public AlertResponse archive(@PathVariable UUID id, Authentication authentication) {
    return service.archive(id, authentication.getName());
  }

  @Operation(
      summary = "Supprimer une alerte",
      description = "Suppression logique : l'alerte et son historique restent en base.")
  @ApiResponse(responseCode = "409", description = CONFLICT, content = @Content)
  @DeleteMapping("/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable UUID id, Authentication authentication) {
    service.delete(id, authentication.getName());
  }

  private static String comment(CommentRequest request) {
    return request == null ? null : request.comment();
  }
}

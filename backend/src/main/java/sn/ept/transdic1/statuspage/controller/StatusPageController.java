package sn.ept.transdic1.statuspage.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import sn.ept.transdic1.statuspage.domain.ComponentSourceType;
import sn.ept.transdic1.statuspage.dto.StatusPageDtos.ComponentDetail;
import sn.ept.transdic1.statuspage.dto.StatusPageDtos.ComponentRequest;
import sn.ept.transdic1.statuspage.dto.StatusPageDtos.IncidentDetail;
import sn.ept.transdic1.statuspage.dto.StatusPageDtos.IncidentRequest;
import sn.ept.transdic1.statuspage.dto.StatusPageDtos.IncidentUpdateRequest;
import sn.ept.transdic1.statuspage.dto.StatusPageDtos.SourceOption;
import sn.ept.transdic1.statuspage.dto.StatusPageDtos.StatusPageDetail;
import sn.ept.transdic1.statuspage.dto.StatusPageDtos.StatusPageRequest;
import sn.ept.transdic1.statuspage.dto.StatusPageDtos.StatusPageSummary;
import sn.ept.transdic1.statuspage.dto.StatusPageDtos.StatusPageView;
import sn.ept.transdic1.statuspage.service.StatusPageAdminService;
import sn.ept.transdic1.statuspage.service.StatusPageQueryService;

@Tag(
    name = "Pages de statut",
    description =
        "Pages de statut des services (ADR 0026) : lecture des pages actives pour tous les rôles"
            + " (composants filtrés par le périmètre de sites), administration réservée à ADMIN"
            + " (10 pages, 50 composants par page), tracée dans le journal d'audit.")
@RestController
@RequestMapping("/api/v1/pages-statut")
public class StatusPageController {
  private final StatusPageAdminService admin;
  private final StatusPageQueryService query;

  public StatusPageController(StatusPageAdminService admin, StatusPageQueryService query) {
    this.admin = admin;
    this.query = query;
  }

  private static boolean isAdmin(Authentication authentication) {
    return authentication.getAuthorities().stream()
        .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
  }

  @Operation(
      summary = "Lister les pages de statut",
      description = "Pages actives (toutes pour un administrateur), avec leur état global.")
  @GetMapping
  public List<StatusPageSummary> list(Authentication authentication) {
    return query.list(isAdmin(authentication));
  }

  @Operation(
      summary = "Voir une page de statut",
      description =
          "Rendu de la page pour un compte connecté : composants de son périmètre de sites."
              + " Une page inactive n'est visible qu'en aperçu par un administrateur.")
  @ApiResponse(responseCode = "404", description = "Page introuvable", content = @Content)
  @GetMapping("/{id}/vue")
  public StatusPageView view(@PathVariable UUID id, Authentication authentication) {
    return query.internalView(id, isAdmin(authentication));
  }

  @Operation(
      summary = "Rechercher une source",
      description = "Sondes, équipements ou sites dont le nom contient q (50 au plus).")
  @GetMapping("/sources")
  @PreAuthorize("hasRole('ADMIN')")
  public List<SourceOption> sources(
      @RequestParam ComponentSourceType type, @RequestParam(required = false) String q) {
    return admin.searchSources(type, q);
  }

  @Operation(summary = "Détail d'une page pour l'administration")
  @ApiResponse(responseCode = "404", description = "Page introuvable", content = @Content)
  @GetMapping("/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  public StatusPageDetail detail(@PathVariable UUID id) {
    return admin.detail(id);
  }

  @Operation(summary = "Créer une page de statut")
  @ApiResponse(responseCode = "400", description = "Saisie invalide", content = @Content)
  @ApiResponse(
      responseCode = "409",
      description = "Identifiant déjà utilisé ou 10 pages atteintes",
      content = @Content)
  @PostMapping
  @PreAuthorize("hasRole('ADMIN')")
  @ResponseStatus(HttpStatus.CREATED)
  public StatusPageDetail create(@Valid @RequestBody StatusPageRequest request) {
    return admin.createPage(request);
  }

  @Operation(summary = "Modifier une page de statut (dont sa visibilité)")
  @PutMapping("/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  public StatusPageDetail update(
      @PathVariable UUID id, @Valid @RequestBody StatusPageRequest request) {
    return admin.updatePage(id, request);
  }

  @Operation(
      summary = "Supprimer une page de statut",
      description = "Avec ses composants et incidents.")
  @DeleteMapping("/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable UUID id) {
    admin.deletePage(id);
  }

  @Operation(summary = "Ajouter un composant")
  @ApiResponse(
      responseCode = "400",
      description = "Saisie invalide ou source inexistante",
      content = @Content)
  @ApiResponse(responseCode = "409", description = "50 composants atteints", content = @Content)
  @PostMapping("/{id}/composants")
  @PreAuthorize("hasRole('ADMIN')")
  @ResponseStatus(HttpStatus.CREATED)
  public ComponentDetail addComponent(
      @PathVariable UUID id, @Valid @RequestBody ComponentRequest request) {
    return admin.addComponent(id, request);
  }

  @Operation(summary = "Modifier un composant")
  @PutMapping("/{id}/composants/{componentId}")
  @PreAuthorize("hasRole('ADMIN')")
  public ComponentDetail updateComponent(
      @PathVariable UUID id,
      @PathVariable UUID componentId,
      @Valid @RequestBody ComponentRequest request) {
    return admin.updateComponent(id, componentId, request);
  }

  @Operation(summary = "Retirer un composant")
  @DeleteMapping("/{id}/composants/{componentId}")
  @PreAuthorize("hasRole('ADMIN')")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteComponent(@PathVariable UUID id, @PathVariable UUID componentId) {
    admin.deleteComponent(id, componentId);
  }

  @Operation(summary = "Déclarer un incident")
  @ApiResponse(responseCode = "400", description = "Saisie invalide", content = @Content)
  @PostMapping("/{id}/incidents")
  @PreAuthorize("hasRole('ADMIN')")
  @ResponseStatus(HttpStatus.CREATED)
  public IncidentDetail createIncident(
      @PathVariable UUID id,
      @Valid @RequestBody IncidentRequest request,
      Authentication authentication) {
    return admin.createIncident(id, request, authentication.getName());
  }

  @Operation(
      summary = "Modifier un incident",
      description = "Un incident ouvert automatiquement peut aussi être modifié.")
  @PutMapping("/{id}/incidents/{incidentId}")
  @PreAuthorize("hasRole('ADMIN')")
  public IncidentDetail updateIncident(
      @PathVariable UUID id,
      @PathVariable UUID incidentId,
      @Valid @RequestBody IncidentRequest request) {
    return admin.updateIncident(id, incidentId, request);
  }

  @Operation(summary = "Supprimer un incident")
  @DeleteMapping("/{id}/incidents/{incidentId}")
  @PreAuthorize("hasRole('ADMIN')")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteIncident(@PathVariable UUID id, @PathVariable UUID incidentId) {
    admin.deleteIncident(id, incidentId);
  }

  @Operation(summary = "Publier une mise à jour d'incident")
  @ApiResponse(responseCode = "409", description = "50 mises à jour atteintes", content = @Content)
  @PostMapping("/{id}/incidents/{incidentId}/mises-a-jour")
  @PreAuthorize("hasRole('ADMIN')")
  @ResponseStatus(HttpStatus.CREATED)
  public IncidentDetail addUpdate(
      @PathVariable UUID id,
      @PathVariable UUID incidentId,
      @Valid @RequestBody IncidentUpdateRequest request,
      Authentication authentication) {
    return admin.addUpdate(id, incidentId, request, authentication.getName());
  }
}

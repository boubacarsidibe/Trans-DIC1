package sn.ept.transdic1.inventory.controller;

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
import sn.ept.transdic1.inventory.domain.SiteStatus;
import sn.ept.transdic1.inventory.dto.SiteRequest;
import sn.ept.transdic1.inventory.dto.SiteResponse;
import sn.ept.transdic1.inventory.service.SiteService;

@Tag(
    name = "Sites",
    description =
        "Emplacements regroupant les équipements. Écriture réservée à ADMIN et NOC_OPERATOR.")
@RestController
@RequestMapping("/api/v1/sites")
public class SiteController {
  private final SiteService service;

  public SiteController(SiteService service) {
    this.service = service;
  }

  @Operation(
      summary = "Lister les sites",
      description = "Liste paginée (20 par page, tri par nom), filtrable par statut.")
  @GetMapping
  public Page<SiteResponse> list(
      @RequestParam(required = false) SiteStatus status,
      @PageableDefault(size = 20, sort = "name") Pageable pageable) {
    return service.list(status, pageable);
  }

  @Operation(summary = "Consulter un site")
  @ApiResponse(responseCode = "404", description = "Site introuvable", content = @Content)
  @GetMapping("/{id}")
  public SiteResponse get(@PathVariable UUID id) {
    return service.get(id);
  }

  @Operation(summary = "Créer un site")
  @ApiResponse(responseCode = "400", description = "Données invalides", content = @Content)
  @ApiResponse(responseCode = "409", description = "Code déjà utilisé", content = @Content)
  @PostMapping
  @PreAuthorize("hasAnyRole('ADMIN','NOC_OPERATOR')")
  @ResponseStatus(HttpStatus.CREATED)
  public SiteResponse create(@Valid @RequestBody SiteRequest request) {
    return service.create(request);
  }

  @Operation(summary = "Modifier un site", description = "Le code ne peut pas changer.")
  @ApiResponse(responseCode = "400", description = "Données invalides", content = @Content)
  @ApiResponse(responseCode = "404", description = "Site introuvable", content = @Content)
  @PutMapping("/{id}")
  @PreAuthorize("hasAnyRole('ADMIN','NOC_OPERATOR')")
  public SiteResponse update(@PathVariable UUID id, @Valid @RequestBody SiteRequest request) {
    return service.update(id, request);
  }

  @Operation(summary = "Supprimer un site vide")
  @ApiResponse(responseCode = "404", description = "Site introuvable", content = @Content)
  @ApiResponse(
      responseCode = "409",
      description = "Le site contient des équipements",
      content = @Content)
  @DeleteMapping("/{id}")
  @PreAuthorize("hasAnyRole('ADMIN','NOC_OPERATOR')")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable UUID id) {
    service.delete(id);
  }
}

package sn.ept.transdic1.access.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import sn.ept.transdic1.access.dto.SiteScopeDtos.UserScope;
import sn.ept.transdic1.access.dto.SiteScopeDtos.UserScopeRequest;
import sn.ept.transdic1.access.service.SiteAccessAdminService;

@Tag(
    name = "Périmètres d'accès",
    description =
        "Sites visibles par un opérateur ou un lecteur ; sans site attribué, tout le parc.")
@RestController
@RequestMapping("/api/v1/perimetres")
@PreAuthorize("hasRole('ADMIN')")
public class SiteAccessController {
  private final SiteAccessAdminService service;

  public SiteAccessController(SiteAccessAdminService service) {
    this.service = service;
  }

  @Operation(summary = "Comptes limités à certains sites")
  @GetMapping
  public List<UserScope> list() {
    return service.list();
  }

  @Operation(
      summary = "Définir les sites d'un compte",
      description =
          "Remplace la liste ; une liste vide rend l'accès à tout le parc. Effet immédiat, tracé"
              + " dans le journal d'audit.")
  @ApiResponse(
      responseCode = "400",
      description = "Site inconnu ou compte administrateur",
      content = @Content)
  @ApiResponse(responseCode = "404", description = "Utilisateur introuvable", content = @Content)
  @PutMapping("/{userId}")
  public UserScope replace(
      @PathVariable UUID userId, @Valid @RequestBody UserScopeRequest request) {
    return service.replace(userId, request.siteIds());
  }
}

package sn.ept.transdic1.overview.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
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
import sn.ept.transdic1.overview.dto.DashboardViewDtos.ViewRequest;
import sn.ept.transdic1.overview.dto.DashboardViewDtos.ViewResponse;
import sn.ept.transdic1.overview.service.DashboardViewService;

@Tag(
    name = "Vues de tableau de bord",
    description =
        "Vues personnelles (widgets ordonnés) pour le tableau de bord et le mur d'écran NOC. Chaque"
            + " utilisateur ne voit et ne modifie que ses propres vues.")
@RestController
@RequestMapping("/api/v1/vues")
public class DashboardViewController {
  private final DashboardViewService service;

  public DashboardViewController(DashboardViewService service) {
    this.service = service;
  }

  @Operation(summary = "Mes vues")
  @GetMapping
  public List<ViewResponse> list(Authentication authentication) {
    return service.list(authentication.getName());
  }

  @Operation(
      summary = "Créer une vue",
      description =
          "Widgets possibles : summary, statuses, severities, open-alerts, down-equipments,"
              + " latest-metric.")
  @ApiResponse(responseCode = "409", description = "Nom déjà utilisé", content = @Content)
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ViewResponse create(
      @Valid @RequestBody ViewRequest request, Authentication authentication) {
    return service.create(authentication.getName(), request);
  }

  @Operation(summary = "Modifier une vue")
  @ApiResponse(responseCode = "404", description = "Vue introuvable", content = @Content)
  @PutMapping("/{id}")
  public ViewResponse update(
      @PathVariable UUID id,
      @Valid @RequestBody ViewRequest request,
      Authentication authentication) {
    return service.update(authentication.getName(), id, request);
  }

  @Operation(summary = "Supprimer une vue")
  @ApiResponse(responseCode = "404", description = "Vue introuvable", content = @Content)
  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable UUID id, Authentication authentication) {
    service.delete(authentication.getName(), id);
  }
}

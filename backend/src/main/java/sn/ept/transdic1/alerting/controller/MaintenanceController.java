package sn.ept.transdic1.alerting.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import sn.ept.transdic1.alerting.domain.MaintenanceState;
import sn.ept.transdic1.alerting.dto.MaintenanceRequest;
import sn.ept.transdic1.alerting.dto.MaintenanceResponse;
import sn.ept.transdic1.alerting.service.MaintenanceService;

@Tag(
    name = "Maintenances",
    description =
        "Fenêtres pendant lesquelles les alertes d'un site ou d'un équipement sont tracées sans notification."
            + " Lecture pour tous, planification et annulation pour ADMIN et NOC_OPERATOR.")
@RestController
@RequestMapping("/api/v1/maintenances")
public class MaintenanceController {
  private final MaintenanceService service;

  public MaintenanceController(MaintenanceService service) {
    this.service = service;
  }

  @Operation(
      summary = "Lister les maintenances",
      description =
          "Liste paginée (20 par page, début le plus récent d'abord), filtrable par état, site et équipement.")
  @GetMapping
  public Page<MaintenanceResponse> list(
      @RequestParam(required = false) MaintenanceState state,
      @RequestParam(required = false) UUID equipmentId,
      @RequestParam(required = false) UUID siteId,
      @PageableDefault(size = 20, sort = "startAt", direction = Sort.Direction.DESC)
          Pageable pageable) {
    return service.list(state, equipmentId, siteId, pageable);
  }

  @Operation(summary = "Planifier une maintenance")
  @ApiResponse(
      responseCode = "400",
      description = "Cible, dates ou motif invalides",
      content = @Content)
  @PostMapping
  @PreAuthorize("hasAnyRole('ADMIN','NOC_OPERATOR')")
  @ResponseStatus(HttpStatus.CREATED)
  public MaintenanceResponse create(
      @Valid @RequestBody MaintenanceRequest request, Authentication authentication) {
    return service.create(request, authentication.getName());
  }

  @Operation(summary = "Annuler une maintenance à venir ou en cours")
  @ApiResponse(responseCode = "404", description = "Maintenance introuvable", content = @Content)
  @ApiResponse(responseCode = "409", description = "Déjà annulée ou terminée", content = @Content)
  @PostMapping("/{id}/annulation")
  @PreAuthorize("hasAnyRole('ADMIN','NOC_OPERATOR')")
  public MaintenanceResponse cancel(@PathVariable UUID id, Authentication authentication) {
    return service.cancel(id, authentication.getName());
  }
}

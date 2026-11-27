package sn.ept.transdic1.alerting.controller;

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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import sn.ept.transdic1.alerting.dto.MaintenanceDemandDecision;
import sn.ept.transdic1.alerting.dto.MaintenanceDemandRequest;
import sn.ept.transdic1.alerting.dto.MaintenanceDemandResponse;
import sn.ept.transdic1.alerting.service.MaintenanceDemandService;

@Tag(
    name = "Demandes de maintenance",
    description =
        "L'opérateur NOC demande une maintenance ; l'administrateur la valide (elle est alors"
            + " planifiée) ou la refuse avec un motif (ADR 0058).")
@RestController
@RequestMapping("/api/v1/maintenances/demandes")
public class MaintenanceDemandController {
  private final MaintenanceDemandService service;

  public MaintenanceDemandController(MaintenanceDemandService service) {
    this.service = service;
  }

  @Operation(
      summary = "Lister les demandes",
      description =
          "L'administrateur voit toutes les demandes de son périmètre, l'opérateur les siennes.")
  @GetMapping
  @PreAuthorize("hasAnyRole('ADMIN','NOC_OPERATOR')")
  public List<MaintenanceDemandResponse> list(Authentication authentication) {
    return service.list(authentication.getName());
  }

  @Operation(summary = "Demander une maintenance")
  @ApiResponse(
      responseCode = "400",
      description = "Cible, dates ou motif invalides",
      content = @Content)
  @PostMapping
  @PreAuthorize("hasAnyRole('ADMIN','NOC_OPERATOR')")
  @ResponseStatus(HttpStatus.CREATED)
  public MaintenanceDemandResponse request(
      @Valid @RequestBody MaintenanceDemandRequest request, Authentication authentication) {
    return service.request(request, authentication.getName());
  }

  @Operation(summary = "Valider une demande : la maintenance est planifiée")
  @ApiResponse(responseCode = "404", description = "Demande introuvable", content = @Content)
  @ApiResponse(responseCode = "409", description = "Demande déjà décidée", content = @Content)
  @PostMapping("/{id}/validation")
  @PreAuthorize("hasRole('ADMIN')")
  public MaintenanceDemandResponse approve(
      @PathVariable UUID id,
      @Valid @RequestBody(required = false) MaintenanceDemandDecision decision,
      Authentication authentication) {
    return service.approve(id, decision, authentication.getName());
  }

  @Operation(summary = "Refuser une demande, avec un motif")
  @ApiResponse(responseCode = "400", description = "Motif manquant", content = @Content)
  @ApiResponse(responseCode = "404", description = "Demande introuvable", content = @Content)
  @ApiResponse(responseCode = "409", description = "Demande déjà décidée", content = @Content)
  @PostMapping("/{id}/refus")
  @PreAuthorize("hasRole('ADMIN')")
  public MaintenanceDemandResponse refuse(
      @PathVariable UUID id,
      @Valid @RequestBody MaintenanceDemandDecision decision,
      Authentication authentication) {
    return service.refuse(id, decision, authentication.getName());
  }
}

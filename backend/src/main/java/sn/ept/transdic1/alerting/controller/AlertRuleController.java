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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import sn.ept.transdic1.alerting.dto.AlertRuleRequest;
import sn.ept.transdic1.alerting.dto.AlertRuleResponse;
import sn.ept.transdic1.alerting.service.AlertRuleService;

@Tag(
    name = "Règles d'alerte",
    description =
        "Seuils, durée de confirmation, seuil de retour et portée. Lecture pour tous les rôles,"
            + " écriture réservée aux administrateurs.")
@RestController
@RequestMapping("/api/v1/regles-alertes")
public class AlertRuleController {
  private final AlertRuleService service;

  public AlertRuleController(AlertRuleService service) {
    this.service = service;
  }

  @Operation(
      summary = "Lister les règles",
      description = "Liste paginée (20 par page, tri par nom).")
  @GetMapping
  public Page<AlertRuleResponse> list(
      @PageableDefault(size = 20, sort = "name") Pageable pageable) {
    return service.list(pageable);
  }

  @Operation(summary = "Consulter une règle")
  @ApiResponse(responseCode = "404", description = "Règle introuvable", content = @Content)
  @GetMapping("/{id}")
  public AlertRuleResponse get(@PathVariable UUID id) {
    return service.get(id);
  }

  @Operation(summary = "Créer une règle")
  @ApiResponse(responseCode = "400", description = "Règle invalide", content = @Content)
  @ApiResponse(responseCode = "409", description = "Nom déjà utilisé", content = @Content)
  @PostMapping
  @PreAuthorize("hasRole('ADMIN')")
  @ResponseStatus(HttpStatus.CREATED)
  public AlertRuleResponse create(@Valid @RequestBody AlertRuleRequest request) {
    return service.create(request);
  }

  @Operation(summary = "Remplacer une règle et sa portée")
  @ApiResponse(responseCode = "400", description = "Règle invalide", content = @Content)
  @ApiResponse(responseCode = "404", description = "Règle introuvable", content = @Content)
  @ApiResponse(responseCode = "409", description = "Nom déjà utilisé", content = @Content)
  @PutMapping("/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  public AlertRuleResponse update(
      @PathVariable UUID id, @Valid @RequestBody AlertRuleRequest request) {
    return service.update(id, request);
  }

  @Operation(
      summary = "Supprimer une règle",
      description = "Refusé si la règle a déjà produit des alertes : désactivez-la.")
  @ApiResponse(responseCode = "404", description = "Règle introuvable", content = @Content)
  @ApiResponse(
      responseCode = "409",
      description = "La règle a produit des alertes",
      content = @Content)
  @DeleteMapping("/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable UUID id) {
    service.delete(id);
  }
}

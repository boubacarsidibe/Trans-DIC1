package sn.ept.transdic1.inventory.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.data.domain.*;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import sn.ept.transdic1.inventory.domain.*;
import sn.ept.transdic1.inventory.dto.*;
import sn.ept.transdic1.inventory.service.EquipmentService;

@Tag(name = "Équipements", description = "Inventaire des équipements supervisés. Écriture réservée aux rôles ADMIN et NOC_OPERATOR.")
@RestController
@RequestMapping("/api/v1/equipements")
public class EquipmentController {
  private final EquipmentService service;

  public EquipmentController(EquipmentService service) {
    this.service = service;
  }

  @Operation(summary = "Lister les équipements", description = "Liste paginée (20 par page, tri par nom), filtrable par site, type et statut.")
  @GetMapping
  public Page<EquipmentResponse> list(
      @RequestParam(required = false) UUID siteId,
      @RequestParam(required = false) EquipmentType type,
      @RequestParam(required = false) EquipmentStatus status,
      @PageableDefault(size = 20, sort = "name") Pageable p) {
    return service.list(siteId, type, status, p);
  }

  @Operation(summary = "Consulter un équipement")
  @ApiResponse(responseCode = "404", description = "Équipement introuvable")
  @GetMapping("/{id}")
  public EquipmentResponse get(@PathVariable UUID id) {
    return service.get(id);
  }

  @Operation(summary = "Créer un équipement")
  @ApiResponse(responseCode = "201", description = "Équipement créé")
  @ApiResponse(responseCode = "400", description = "Données invalides")
  @ApiResponse(responseCode = "409", description = "Code d'inventaire déjà utilisé")
  @PostMapping
  @PreAuthorize("hasAnyRole('ADMIN','NOC_OPERATOR')")
  public ResponseEntity<EquipmentResponse> create(@Valid @RequestBody EquipmentRequest r) {
    var response = service.create(r);
    return ResponseEntity.created(URI.create("/api/v1/equipements/" + response.id()))
        .body(response);
  }

  @Operation(summary = "Modifier un équipement")
  @ApiResponse(responseCode = "400", description = "Données invalides")
  @ApiResponse(responseCode = "404", description = "Équipement introuvable")
  @ApiResponse(responseCode = "409", description = "Code d'inventaire déjà utilisé")
  @PutMapping("/{id}")
  @PreAuthorize("hasAnyRole('ADMIN','NOC_OPERATOR')")
  public EquipmentResponse update(@PathVariable UUID id, @Valid @RequestBody EquipmentRequest r) {
    return service.update(id, r);
  }

  @Operation(summary = "Supprimer un équipement")
  @ApiResponse(responseCode = "204", description = "Équipement supprimé")
  @ApiResponse(responseCode = "404", description = "Équipement introuvable")
  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasAnyRole('ADMIN','NOC_OPERATOR')")
  public void delete(@PathVariable UUID id) {
    service.delete(id);
  }
}

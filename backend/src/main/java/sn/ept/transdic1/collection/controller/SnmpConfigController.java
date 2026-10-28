package sn.ept.transdic1.collection.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import sn.ept.transdic1.collection.dto.SnmpConfigRequest;
import sn.ept.transdic1.collection.dto.SnmpConfigResponse;
import sn.ept.transdic1.collection.service.SnmpConfigService;

@Tag(
    name = "Collecte SNMP",
    description =
        "Configuration de la lecture SNMP d'un équipement. Secrets par référence (env:NOM) uniquement.")
@RestController
@RequestMapping("/api/v1/equipements/{equipmentId}/collecte-snmp")
@PreAuthorize("hasRole('ADMIN')")
public class SnmpConfigController {
  private final SnmpConfigService service;

  public SnmpConfigController(SnmpConfigService service) {
    this.service = service;
  }

  @Operation(summary = "Consulter la collecte SNMP d'un équipement")
  @ApiResponse(responseCode = "404", description = "Aucune collecte configurée", content = @Content)
  @GetMapping
  public SnmpConfigResponse get(@PathVariable UUID equipmentId) {
    return service.get(equipmentId);
  }

  @Operation(
      summary = "Configurer la collecte SNMP",
      description =
          "Crée ou remplace la configuration. Communauté et mots de passe se donnent par référence"
              + " (env:NOM), jamais en clair. La première lecture a lieu à la prochaine passe.")
  @ApiResponse(responseCode = "400", description = "Configuration invalide", content = @Content)
  @ApiResponse(responseCode = "404", description = "Équipement introuvable", content = @Content)
  @PutMapping
  public SnmpConfigResponse put(
      @PathVariable UUID equipmentId, @Valid @RequestBody SnmpConfigRequest request) {
    return service.put(equipmentId, request);
  }

  @Operation(
      summary = "Arrêter la collecte SNMP",
      description = "Désactive la collecte ; les mesures déjà reçues sont conservées.")
  @ApiResponse(responseCode = "404", description = "Aucune collecte configurée", content = @Content)
  @DeleteMapping
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable UUID equipmentId) {
    service.delete(equipmentId);
  }
}

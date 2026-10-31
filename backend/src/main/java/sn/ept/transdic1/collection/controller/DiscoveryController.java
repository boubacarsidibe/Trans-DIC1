package sn.ept.transdic1.collection.controller;

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
import sn.ept.transdic1.collection.dto.DiscoveryDtos.DiscoveryRequest;
import sn.ept.transdic1.collection.dto.DiscoveryDtos.DiscoveryScanResponse;
import sn.ept.transdic1.collection.dto.DiscoveryDtos.DiscoverySettingsResponse;
import sn.ept.transdic1.collection.service.DiscoveryService;

@Tag(
    name = "Découverte",
    description =
        "Recherche SNMP d'équipements sur une plage autorisée par l'exploitation. Les équipements"
            + " trouvés sont proposés, jamais créés automatiquement. Réservé aux administrateurs.")
@RestController
@RequestMapping("/api/v1/decouverte")
@PreAuthorize("hasRole('ADMIN')")
public class DiscoveryController {
  private final DiscoveryService service;

  public DiscoveryController(DiscoveryService service) {
    this.service = service;
  }

  @Operation(summary = "Plages autorisées et limites de la découverte")
  @GetMapping("/parametres")
  public DiscoverySettingsResponse settings() {
    return service.settings();
  }

  @Operation(summary = "Dernières recherches")
  @GetMapping
  public List<DiscoveryScanResponse> recent() {
    return service.recent();
  }

  @Operation(summary = "Consulter une recherche et les équipements trouvés")
  @ApiResponse(responseCode = "404", description = "Recherche introuvable", content = @Content)
  @GetMapping("/{id}")
  public DiscoveryScanResponse get(@PathVariable UUID id) {
    return service.get(id);
  }

  @Operation(
      summary = "Lancer une recherche",
      description =
          "La plage doit être incluse dans une plage autorisée (DISCOVERY_ALLOWED_RANGES). La"
              + " communauté ou la phrase SNMPv3 se donne par référence env:NOM. La recherche"
              + " tourne en arrière-plan : suivre son avancement avec GET /{id}.")
  @ApiResponse(responseCode = "400", description = "Plage ou secret invalide", content = @Content)
  @ApiResponse(responseCode = "403", description = "Plage non autorisée", content = @Content)
  @ApiResponse(responseCode = "409", description = "Recherche déjà en cours", content = @Content)
  @PostMapping
  @ResponseStatus(HttpStatus.ACCEPTED)
  public DiscoveryScanResponse start(
      @Valid @RequestBody DiscoveryRequest request, Authentication authentication) {
    return service.start(request, authentication.getName());
  }
}

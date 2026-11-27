package sn.ept.transdic1.collection.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import sn.ept.transdic1.collection.dto.AgentPrincipal;
import sn.ept.transdic1.collection.dto.ServiceDiscoveryDtos.DiscoveredService;
import sn.ept.transdic1.collection.dto.ServiceDiscoveryDtos.DiscoveredServices;
import sn.ept.transdic1.collection.dto.ServiceDiscoveryDtos.FollowRequest;
import sn.ept.transdic1.collection.dto.ServiceDiscoveryDtos.FollowedNames;
import sn.ept.transdic1.collection.service.ServiceDiscoveryService;

@Tag(
    name = "Services découverts",
    description =
        "Services système actifs et ports en écoute vus par l'agent ; suivre un service en un clic"
            + " ajoute service.up[nom] sans toucher à la configuration de l'agent (ADR 0056).")
@RestController
@RequestMapping("/api/v1")
public class ServiceDiscoveryController {
  private final ServiceDiscoveryService discovery;

  public ServiceDiscoveryController(ServiceDiscoveryService discovery) {
    this.discovery = discovery;
  }

  @Operation(summary = "Services actifs et ports en écoute d'un équipement")
  @ApiResponse(
      responseCode = "404",
      description = "Équipement introuvable ou aucun inventaire reçu",
      content = @Content)
  @GetMapping("/equipements/{equipmentId}/services-decouverts")
  DiscoveredServices discovered(@PathVariable UUID equipmentId) {
    return discovery.discovered(equipmentId);
  }

  @Operation(summary = "Suivre un service système de l'équipement")
  @ApiResponse(
      responseCode = "409",
      description = "Nom déjà pris par une sonde",
      content = @Content)
  @PostMapping("/equipements/{equipmentId}/services-suivis")
  @PreAuthorize("hasRole('ADMIN')")
  @ResponseStatus(HttpStatus.CREATED)
  DiscoveredService follow(
      @PathVariable UUID equipmentId, @Valid @RequestBody FollowRequest request) {
    return discovery.follow(equipmentId, request.nom());
  }

  @Operation(summary = "Cesser de suivre un service système")
  @DeleteMapping("/equipements/{equipmentId}/services-suivis/{serviceId}")
  @PreAuthorize("hasRole('ADMIN')")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void unfollow(@PathVariable UUID equipmentId, @PathVariable UUID serviceId) {
    discovery.unfollow(equipmentId, serviceId);
  }

  @Operation(
      summary = "Services à suivre (agent)",
      description = "Authentifié par le jeton de l'agent : les noms dont il remonte l'état.")
  @GetMapping("/agent/services-suivis")
  @PreAuthorize("hasRole('AGENT')")
  FollowedNames followed(@AuthenticationPrincipal AgentPrincipal agent) {
    return discovery.followedNames(agent.equipmentId());
  }
}

package sn.ept.transdic1.inventory.controller;

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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import sn.ept.transdic1.inventory.dto.TopologyDtos.Graph;
import sn.ept.transdic1.inventory.dto.TopologyDtos.Impact;
import sn.ept.transdic1.inventory.dto.TopologyDtos.Link;
import sn.ept.transdic1.inventory.dto.TopologyDtos.LinkRequest;
import sn.ept.transdic1.inventory.service.TopologyService;

@Tag(
    name = "Topologie",
    description =
        "Dépendances entre équipements et impact d'une panne. Consultation pour tous les rôles,"
            + " modification des liens par les administrateurs.")
@RestController
@RequestMapping("/api/v1/topologie")
public class TopologyController {
  private final TopologyService service;

  public TopologyController(TopologyService service) {
    this.service = service;
  }

  @Operation(summary = "Graphe complet : équipements et liens vers l'amont")
  @GetMapping
  public Graph graph() {
    return service.graph();
  }

  @Operation(summary = "Déclarer qu'un équipement dépend d'un équipement amont")
  @ApiResponse(responseCode = "409", description = "Lien existant ou boucle", content = @Content)
  @PostMapping("/liens")
  @PreAuthorize("hasRole('ADMIN')")
  @ResponseStatus(HttpStatus.CREATED)
  public Link addLink(@Valid @RequestBody LinkRequest request) {
    return service.addLink(request.equipmentId(), request.upstreamId());
  }

  @Operation(summary = "Supprimer un lien")
  @ApiResponse(responseCode = "404", description = "Lien introuvable", content = @Content)
  @DeleteMapping("/liens/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void removeLink(@PathVariable UUID id) {
    service.removeLink(id);
  }

  @Operation(
      summary = "Impact d'une panne",
      description = "Équipements en aval, rendus injoignables si celui-ci tombe.")
  @GetMapping("/impact/{equipmentId}")
  public Impact impact(@PathVariable UUID equipmentId) {
    return service.impact(equipmentId);
  }
}

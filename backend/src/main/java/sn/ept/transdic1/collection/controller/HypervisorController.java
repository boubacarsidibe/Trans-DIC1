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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import sn.ept.transdic1.collection.dto.HypervisorDtos.ConnectorRequest;
import sn.ept.transdic1.collection.dto.HypervisorDtos.ConnectorResponse;
import sn.ept.transdic1.collection.dto.HypervisorDtos.ResourceResponse;
import sn.ept.transdic1.collection.dto.HypervisorDtos.SyncReport;
import sn.ept.transdic1.collection.dto.HypervisorDtos.TestResult;
import sn.ept.transdic1.collection.service.HypervisorConnectorService;

@Tag(
    name = "Hyperviseurs",
    description =
        "Connecteurs d'hyperviseur (Proxmox VE) : hôtes, VM et conteneurs vus sans agent, avec leur"
            + " lien VM → hôte et leurs mesures (ADR 0055).")
@RestController
@RequestMapping("/api/v1/hyperviseurs")
public class HypervisorController {
  private final HypervisorConnectorService connectors;

  public HypervisorController(HypervisorConnectorService connectors) {
    this.connectors = connectors;
  }

  @Operation(summary = "Lister les connecteurs d'hyperviseur")
  @GetMapping
  @PreAuthorize("hasRole('ADMIN')")
  List<ConnectorResponse> list() {
    return connectors.list();
  }

  @Operation(summary = "Lire un connecteur d'hyperviseur")
  @ApiResponse(responseCode = "404", description = "Connecteur introuvable", content = @Content)
  @GetMapping("/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  ConnectorResponse get(@PathVariable UUID id) {
    return connectors.get(id);
  }

  @Operation(
      summary = "Créer un connecteur d'hyperviseur",
      description =
          "Adresse HTTPS de l'API, identifiant du jeton en lecture seule et référence env:NOM de son"
              + " secret (jamais le secret), autorité de certification interne facultative.")
  @ApiResponse(responseCode = "400", description = "Réglage invalide", content = @Content)
  @PostMapping
  @PreAuthorize("hasRole('ADMIN')")
  @ResponseStatus(HttpStatus.CREATED)
  ConnectorResponse create(@Valid @RequestBody ConnectorRequest request) {
    return connectors.create(request);
  }

  @Operation(summary = "Modifier un connecteur d'hyperviseur")
  @ApiResponse(responseCode = "404", description = "Connecteur introuvable", content = @Content)
  @PutMapping("/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  ConnectorResponse update(@PathVariable UUID id, @Valid @RequestBody ConnectorRequest request) {
    return connectors.update(id, request);
  }

  @Operation(
      summary = "Supprimer un connecteur d'hyperviseur",
      description = "Les équipements découverts restent dans l'inventaire, avec leur historique.")
  @DeleteMapping("/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void delete(@PathVariable UUID id) {
    connectors.delete(id);
  }

  @Operation(summary = "Hôtes, VM et conteneurs vus par le connecteur")
  @GetMapping("/{id}/ressources")
  @PreAuthorize("hasRole('ADMIN')")
  List<ResourceResponse> resources(@PathVariable UUID id) {
    return connectors.resources(id);
  }

  @Operation(summary = "Essayer la connexion à l'hyperviseur, sans rien créer")
  @PostMapping("/{id}/essai")
  @PreAuthorize("hasRole('ADMIN')")
  TestResult test(@PathVariable UUID id) {
    return connectors.test(id);
  }

  @Operation(summary = "Synchroniser maintenant, sans attendre l'intervalle")
  @PostMapping("/{id}/synchronisation")
  @PreAuthorize("hasRole('ADMIN')")
  SyncReport synchronize(@PathVariable UUID id) {
    return connectors.synchronizeNow(id);
  }
}

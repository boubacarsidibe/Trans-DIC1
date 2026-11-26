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
import sn.ept.transdic1.collection.dto.HypervisorDtos.VmActionResponse;
import sn.ept.transdic1.collection.dto.HypervisorDtos.VmResetRequest;
import sn.ept.transdic1.collection.service.HypervisorActionService;
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
  private final HypervisorActionService vmActions;

  public HypervisorController(
      HypervisorConnectorService connectors, HypervisorActionService vmActions) {
    this.connectors = connectors;
    this.vmActions = vmActions;
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

  @Operation(summary = "Demandes de redémarrage de VM d'un connecteur (ADR 0057)")
  @GetMapping("/{id}/actions-vm")
  @PreAuthorize("hasRole('ADMIN')")
  List<VmActionResponse> vmActions(@PathVariable UUID id) {
    return vmActions.list(id);
  }

  @Operation(
      summary = "Demander le redémarrage forcé d'une VM bloquée",
      description =
          "Rien ne part avant l'approbation d'un autre administrateur. Le connecteur doit avoir un"
              + " second jeton (droit VM.PowerMgmt seul) et les actions sur les VM activées.")
  @ApiResponse(
      responseCode = "409",
      description = "Actions désactivées, ressource qui n'est pas une VM, demande déjà en attente",
      content = @Content)
  @PostMapping("/{id}/ressources/{ressourceId}/redemarrage")
  @PreAuthorize("hasRole('ADMIN')")
  @ResponseStatus(HttpStatus.CREATED)
  VmActionResponse requestReset(
      @PathVariable UUID id,
      @PathVariable UUID ressourceId,
      @Valid @RequestBody VmResetRequest request) {
    return vmActions.request(id, ressourceId, request.motif());
  }

  @Operation(summary = "Approuver un redémarrage de VM : l'ordre part aussitôt")
  @ApiResponse(
      responseCode = "409",
      description = "Demande déjà traitée, expirée, ou approbateur identique au demandeur",
      content = @Content)
  @PostMapping("/actions-vm/{actionId}/approbation")
  @PreAuthorize("hasRole('ADMIN')")
  VmActionResponse approveReset(@PathVariable UUID actionId) {
    return vmActions.approve(actionId);
  }

  @Operation(summary = "Refuser un redémarrage de VM")
  @PostMapping("/actions-vm/{actionId}/refus")
  @PreAuthorize("hasRole('ADMIN')")
  VmActionResponse rejectReset(@PathVariable UUID actionId) {
    return vmActions.reject(actionId);
  }
}

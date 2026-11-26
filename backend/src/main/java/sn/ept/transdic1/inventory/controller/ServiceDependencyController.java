package sn.ept.transdic1.inventory.controller;

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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import sn.ept.transdic1.inventory.dto.ServiceDependencyDtos.DependencyRequest;
import sn.ept.transdic1.inventory.dto.ServiceDependencyDtos.DependencyResponse;
import sn.ept.transdic1.inventory.dto.ServiceDependencyDtos.ImportRequest;
import sn.ept.transdic1.inventory.dto.ServiceDependencyDtos.ImportResult;
import sn.ept.transdic1.inventory.dto.ServiceDependencyDtos.ServiceNode;
import sn.ept.transdic1.inventory.service.ServiceDependencyService;

@Tag(
    name = "Dépendances des services",
    description =
        "Un service dépend d'un autre service : chaîne parcourue par la recherche de cause racine"
            + " (ADR 0056). Lecture pour tous, saisie pour les opérateurs NOC et administrateurs.")
@RestController
@RequestMapping("/api/v1/dependances")
public class ServiceDependencyController {
  private final ServiceDependencyService dependencies;

  public ServiceDependencyController(ServiceDependencyService dependencies) {
    this.dependencies = dependencies;
  }

  @Operation(summary = "Lister les dépendances entre services")
  @GetMapping
  List<DependencyResponse> list() {
    return dependencies.list();
  }

  @Operation(summary = "Services que l'on peut relier (sondes et services suivis)")
  @GetMapping("/services")
  List<ServiceNode> services() {
    return dependencies.nodes();
  }

  @Operation(
      summary = "Déclarer qu'un service dépend d'un autre",
      description = "Une dépendance qui fermerait un cycle est refusée (409).")
  @ApiResponse(responseCode = "409", description = "Déjà présente, ou cycle", content = @Content)
  @PostMapping
  @PreAuthorize("hasAnyRole('ADMIN','NOC_OPERATOR')")
  @ResponseStatus(HttpStatus.CREATED)
  DependencyResponse create(@Valid @RequestBody DependencyRequest request) {
    return dependencies.create(request);
  }

  @Operation(summary = "Supprimer une dépendance")
  @DeleteMapping("/{id}")
  @PreAuthorize("hasAnyRole('ADMIN','NOC_OPERATOR')")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void delete(@PathVariable UUID id) {
    dependencies.delete(id);
  }

  @Operation(
      summary = "Importer des dépendances (CSV)",
      description =
          "Une ligne par dépendance : code_equipement;service;code_equipement;service (le premier"
              + " service dépend du second). Chaque ligne est jugée seule.")
  @PostMapping("/import")
  @PreAuthorize("hasAnyRole('ADMIN','NOC_OPERATOR')")
  ImportResult importCsv(@Valid @RequestBody ImportRequest request) {
    return dependencies.importCsv(request.csv());
  }
}

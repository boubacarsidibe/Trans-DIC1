package sn.ept.transdic1.collection.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
import sn.ept.transdic1.collection.dto.ProbeRequestDto;
import sn.ept.transdic1.collection.dto.ProbeResponse;
import sn.ept.transdic1.collection.dto.ProbeTestResult;
import sn.ept.transdic1.collection.service.ProbeAdminService;

@Tag(
    name = "Sondes",
    description =
        "Contrôles de disponibilité lancés depuis le serveur, sans agent (port TCP, HTTP, HTTPS,"
            + " ICMP, certificat TLS). Lecture selon le périmètre par site, écriture réservée aux"
            + " administrateurs.")
@RestController
@RequestMapping("/api/v1/equipements/{equipmentId}/sondes")
public class ProbeController {
  private final ProbeAdminService service;

  public ProbeController(ProbeAdminService service) {
    this.service = service;
  }

  @Operation(
      operationId = "listProbes",
      summary = "Lister les sondes d'un équipement",
      description = "Avec le dernier résultat de chaque sonde (état, latence, dernière erreur).")
  @ApiResponse(responseCode = "404", description = "Équipement introuvable", content = @Content)
  @GetMapping
  public List<ProbeResponse> list(@PathVariable UUID equipmentId) {
    return service.list(equipmentId);
  }

  @Operation(
      operationId = "createProbe",
      summary = "Créer une sonde",
      description =
          "Intervalle d'au moins 30 s, délai de 100 à 30 000 ms, 50 sondes actives au plus par"
              + " équipement. La cible est contrôlée (boucle locale, lien local et métadonnées"
              + " refusés). Première exécution au prochain passage.")
  @ApiResponse(responseCode = "201", description = "Sonde créée")
  @ApiResponse(responseCode = "400", description = "Sonde invalide", content = @Content)
  @ApiResponse(responseCode = "404", description = "Équipement introuvable", content = @Content)
  @ApiResponse(
      responseCode = "409",
      description = "Nom déjà utilisé sur cet équipement",
      content = @Content)
  @PostMapping
  @PreAuthorize("hasRole('ADMIN')")
  public ResponseEntity<ProbeResponse> create(
      @PathVariable UUID equipmentId, @Valid @RequestBody ProbeRequestDto request) {
    var response = service.create(equipmentId, request);
    return ResponseEntity.created(
            URI.create("/api/v1/equipements/" + equipmentId + "/sondes/" + response.id()))
        .body(response);
  }

  @Operation(
      operationId = "updateProbe",
      summary = "Modifier une sonde",
      description = "Prise en compte au prochain passage.")
  @ApiResponse(responseCode = "400", description = "Sonde invalide", content = @Content)
  @ApiResponse(responseCode = "404", description = "Sonde introuvable", content = @Content)
  @ApiResponse(
      responseCode = "409",
      description = "Nom déjà utilisé sur cet équipement",
      content = @Content)
  @PutMapping("/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  public ProbeResponse update(
      @PathVariable UUID equipmentId,
      @PathVariable UUID id,
      @Valid @RequestBody ProbeRequestDto request) {
    return service.update(equipmentId, id, request);
  }

  @Operation(
      operationId = "deleteProbe",
      summary = "Supprimer une sonde",
      description =
          "Une sonde qui a déjà des mesures ou des alertes est désactivée, pour garder"
              + " l'historique.")
  @ApiResponse(responseCode = "204", description = "Sonde supprimée ou désactivée")
  @ApiResponse(responseCode = "404", description = "Sonde introuvable", content = @Content)
  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasRole('ADMIN')")
  public void delete(@PathVariable UUID equipmentId, @PathVariable UUID id) {
    service.delete(equipmentId, id);
  }

  @Operation(
      operationId = "testProbe",
      summary = "Tester une sonde",
      description = "Exécution immédiate ; rien n'est enregistré et aucune alerte n'est ouverte.")
  @ApiResponse(responseCode = "404", description = "Sonde introuvable", content = @Content)
  @PostMapping("/{id}/tester")
  @PreAuthorize("hasRole('ADMIN')")
  public ProbeTestResult test(@PathVariable UUID equipmentId, @PathVariable UUID id) {
    return service.test(equipmentId, id);
  }
}

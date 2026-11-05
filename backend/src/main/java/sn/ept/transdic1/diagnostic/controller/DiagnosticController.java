package sn.ept.transdic1.diagnostic.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import sn.ept.transdic1.diagnostic.dto.DiagnosticResponse;
import sn.ept.transdic1.diagnostic.service.DiagnosticService;

@Tag(name = "Alertes")
@RestController
@RequestMapping("/api/v1/alertes")
public class DiagnosticController {
  private final DiagnosticService service;

  public DiagnosticController(DiagnosticService service) {
    this.service = service;
  }

  @Operation(
      summary = "Diagnostiquer une alerte",
      description =
          "Calculé à la demande, de façon déterministe (ADR 0024) : résumé, impact, causes"
              + " probables classées avec leurs preuves, alertes voisines, événements et"
              + " changements récents, contexte des mesures, incidents similaires et pistes."
              + " Tous les rôles ; même périmètre par site que la lecture de l'alerte.")
  @ApiResponse(responseCode = "200", description = "Diagnostic calculé")
  @ApiResponse(responseCode = "404", description = "Alerte introuvable", content = @Content)
  @GetMapping("/{id}/diagnostic")
  public DiagnosticResponse diagnostic(@PathVariable UUID id) {
    return service.diagnose(id);
  }
}

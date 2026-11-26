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
import sn.ept.transdic1.diagnostic.dto.RootCauseResponse;
import sn.ept.transdic1.diagnostic.service.RootCauseService;

@Tag(name = "Alertes")
@RestController
@RequestMapping("/api/v1")
public class RootCauseController {
  private static final String DESCRIPTION =
      "Calculée à la demande (ADR 0056) : depuis le service ou l'équipement en panne, la chaîne"
          + " est descendue — services dont il dépend, équipement où il tourne, ressources"
          + " saturées, hôte et amont réseau — jusqu'à la cause la plus basse en panne au même"
          + " moment, avec ses preuves. Plusieurs causes sont classées par les poids du diagnostic."
          + " Tous les rôles ; même périmètre par site que la lecture de l'alerte.";

  private final RootCauseService rootCause;

  public RootCauseController(RootCauseService rootCause) {
    this.rootCause = rootCause;
  }

  @Operation(summary = "Cause racine d'une alerte", description = DESCRIPTION)
  @ApiResponse(responseCode = "404", description = "Alerte introuvable", content = @Content)
  @GetMapping("/alertes/{id}/cause-racine")
  public RootCauseResponse forAlert(@PathVariable UUID id) {
    return rootCause.forAlert(id);
  }

  @Operation(summary = "Cause racine d'un incident", description = DESCRIPTION)
  @ApiResponse(responseCode = "404", description = "Incident introuvable", content = @Content)
  @GetMapping("/incidents/{id}/cause-racine")
  public RootCauseResponse forIncident(@PathVariable UUID id) {
    return rootCause.forIncident(id);
  }
}

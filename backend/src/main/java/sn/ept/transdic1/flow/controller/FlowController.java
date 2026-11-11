package sn.ept.transdic1.flow.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import sn.ept.transdic1.flow.service.FlowQueryService;

/**
 * Écran Trafic : ce que les flux NetFlow disent du réseau — combien d'octets, qui parle le plus, à
 * qui, pour quels services. Tous les rôles y accèdent, dans leur périmètre de sites.
 */
@Tag(name = "Trafic", description = "Flux réseau rapportés par les équipements (NetFlow).")
@RestController
@RequestMapping("/api/v1/trafic")
public class FlowController {
  private final FlowQueryService service;

  public FlowController(FlowQueryService service) {
    this.service = service;
  }

  @Operation(
      summary = "Vue d'ensemble du trafic",
      description =
          "Totaux de la période, équipements exportateurs, plus grosses conversations, adresses"
              + " les plus bavardes, services et courbe par minute. Les totaux restent justes"
              + " même quand le détail a été plafonné ; la réponse le signale.")
  @GetMapping
  public FlowQueryService.TrafficOverview overview(
      @Parameter(description = "Fenêtre en heures (1 à 168) ; par défaut 3")
          @RequestParam(required = false)
          Integer heures,
      @Parameter(description = "Limiter à un équipement exportateur")
          @RequestParam(required = false)
          UUID equipement) {
    return service.overview(heures, equipement);
  }
}

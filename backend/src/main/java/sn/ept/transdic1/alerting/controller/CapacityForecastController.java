package sn.ept.transdic1.alerting.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import sn.ept.transdic1.alerting.dto.CapacityForecastResponse;
import sn.ept.transdic1.alerting.service.CapacityForecastService;

@Tag(name = "Métriques", description = "Mesures collectées sur les équipements.")
@RestController
@RequestMapping("/api/v1/equipements/{equipmentId}/previsions")
public class CapacityForecastController {
  private final CapacityForecastService service;

  public CapacityForecastController(CapacityForecastService service) {
    this.service = service;
  }

  @Operation(
      summary = "Prévision de saturation d'un équipement",
      description =
          "Pour chaque indicateur suivi (disque et mémoire en pourcentage par défaut) : tendance"
              + " linéaire sur les 14 derniers jours de moyennes horaires, jours restants avant 100 %"
              + " et confiance. Lecture seule, ouverte à tous les rôles dans leur périmètre de sites.")
  @GetMapping
  public List<CapacityForecastResponse> forecasts(@PathVariable UUID equipmentId) {
    return service.forecasts(equipmentId);
  }
}

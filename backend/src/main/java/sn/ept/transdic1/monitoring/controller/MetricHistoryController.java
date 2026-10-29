package sn.ept.transdic1.monitoring.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import sn.ept.transdic1.monitoring.dto.MetricHistoryResponse;
import sn.ept.transdic1.monitoring.dto.MetricKeyResponse;
import sn.ept.transdic1.monitoring.service.MetricHistoryService;
import sn.ept.transdic1.monitoring.service.MetricHistoryService.Step;

@Tag(name = "Métriques", description = "Mesures collectées sur les équipements.")
@RestController
@RequestMapping("/api/v1/equipements/{equipmentId}/metriques")
public class MetricHistoryController {
  private final MetricHistoryService service;

  public MetricHistoryController(MetricHistoryService service) {
    this.service = service;
  }

  @Operation(
      summary = "Indicateurs d'un équipement",
      description = "Indicateurs mesurés ces 30 derniers jours, avec leur dernière valeur.")
  @GetMapping("/indicateurs")
  public List<MetricKeyResponse> keys(@PathVariable UUID equipmentId) {
    return service.keys(equipmentId);
  }

  @Operation(
      summary = "Historique d'un indicateur",
      description =
          "Points agrégés (moyenne, minimum, maximum) d'un indicateur sur une période. Par défaut"
              + " les dernières 24 heures ; le pas AUTO choisit RAW (3 h au plus), MIN5, HOUR ou"
              + " DAY. Période : 400 jours au plus, 5 000 points au plus.")
  @GetMapping
  public MetricHistoryResponse history(
      @PathVariable UUID equipmentId,
      @RequestParam String key,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant to,
      @RequestParam(defaultValue = "AUTO") Step step) {
    return service.history(equipmentId, key, from, to, step);
  }
}

package sn.ept.transdic1.monitoring.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.*;
import sn.ept.transdic1.monitoring.dto.MetricResponse;
import sn.ept.transdic1.monitoring.service.MetricService;

@Tag(name = "Métriques", description = "Mesures collectées sur les équipements.")
@RestController
@RequestMapping("/api/v1/metrics")
public class MetricController {
  private final MetricService service;

  public MetricController(MetricService service) {
    this.service = service;
  }

  @Operation(summary = "Dernières métriques", description = "Dernière mesure connue par équipement et par indicateur.")
  @GetMapping("/latest")
  public List<MetricResponse> latest() {
    return service.latest();
  }
}

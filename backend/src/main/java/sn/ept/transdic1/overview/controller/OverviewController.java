package sn.ept.transdic1.overview.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import sn.ept.transdic1.overview.dto.OverviewResponse;
import sn.ept.transdic1.overview.service.OverviewService;

@Tag(name = "Vue d'ensemble", description = "Chiffres clés du tableau de bord.")
@RestController
@RequestMapping("/api/v1/vue-ensemble")
public class OverviewController {
  private final OverviewService service;

  public OverviewController(OverviewService service) {
    this.service = service;
  }

  @Operation(
      summary = "Chiffres clés",
      description = "Équipements par statut, alertes actives par sévérité, agents actifs.")
  @GetMapping
  public OverviewResponse overview() {
    return service.overview();
  }
}

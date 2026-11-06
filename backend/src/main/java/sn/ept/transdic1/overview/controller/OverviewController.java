package sn.ept.transdic1.overview.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import sn.ept.transdic1.overview.dto.DashboardResponse;
import sn.ept.transdic1.overview.dto.OverviewResponse;
import sn.ept.transdic1.overview.service.DashboardService;
import sn.ept.transdic1.overview.service.OverviewService;

@Tag(name = "Vue d'ensemble", description = "Chiffres clés du tableau de bord.")
@RestController
@RequestMapping("/api/v1/vue-ensemble")
public class OverviewController {
  private final OverviewService service;

  private final DashboardService dashboard;

  public OverviewController(OverviewService service, DashboardService dashboard) {
    this.service = service;
    this.dashboard = dashboard;
  }

  @Operation(
      summary = "Chiffres clés",
      description = "Équipements par statut, alertes actives par sévérité, agents actifs.")
  @GetMapping
  public OverviewResponse overview() {
    return service.overview();
  }

  @Operation(
      summary = "Écran d'accueil",
      description =
          "Parc complet (état, pire alerte active, dernières mesures), problèmes en cours et"
              + " alertes ouvertes par heure sur 24 h, limités au périmètre de l'utilisateur.")
  @GetMapping("/tableau")
  public DashboardResponse dashboard() {
    return dashboard.dashboard();
  }
}

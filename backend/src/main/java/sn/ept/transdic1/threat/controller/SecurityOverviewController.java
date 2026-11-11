package sn.ept.transdic1.threat.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import sn.ept.transdic1.threat.service.SecurityQueryService;

/**
 * Écran Sécurité : ce qui a été repéré récemment dans les journaux, d'où cela vient et ce qui est
 * visé. Tous les rôles y accèdent, dans leur périmètre de sites.
 */
@Tag(name = "Sécurité", description = "Journaux de sécurité et attaques repérées.")
@RestController
@RequestMapping("/api/v1/securite")
public class SecurityOverviewController {
  private final SecurityQueryService service;

  public SecurityOverviewController(SecurityQueryService service) {
    this.service = service;
  }

  @Operation(
      summary = "Vue d'ensemble de la sécurité",
      description =
          "Chiffres de la période, adresses les plus actives, équipements visés et dernières"
              + " lignes retenues. Une requête web ordinaire n'y figure jamais.")
  @GetMapping
  public SecurityQueryService.SecurityOverview overview(
      @Parameter(description = "Fenêtre en heures (1 à 168) ; par défaut 24")
          @RequestParam(required = false)
          Integer heures) {
    return service.overview(heures);
  }
}

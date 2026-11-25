package sn.ept.transdic1.risk.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import sn.ept.transdic1.audit.service.AuditService;
import sn.ept.transdic1.risk.dto.RiskDtos.ModelCard;
import sn.ept.transdic1.risk.dto.RiskDtos.RiskOverview;
import sn.ept.transdic1.risk.service.RiskService;

@Tag(
    name = "Risque de panne",
    description =
        "Prédiction explicable du risque de panne à 24 heures (ADR 0052), apprise sur"
            + " l'historique de la plateforme")
@RestController
@RequestMapping("/api/v1/risques")
public class RiskController {
  private final RiskService service;
  private final AuditService audit;

  public RiskController(RiskService service, AuditService audit) {
    this.service = service;
    this.audit = audit;
  }

  @Operation(
      summary = "Équipements les plus à risque",
      description =
          "Probabilité de panne (perte de collecte ou alerte critique) dans les 24 heures, avec les"
              + " trois faits qui la poussent le plus, et la fiche du modèle. Tous les rôles ;"
              + " limité au périmètre par site.")
  @GetMapping
  public RiskOverview overview(@RequestParam(defaultValue = "10") int limit) {
    return service.overview(Math.min(Math.max(limit, 1), 200));
  }

  @Operation(
      summary = "Fiche du modèle de risque",
      description =
          "Poids de chaque variable (appris et initial), facteur de risque, volume d'apprentissage"
              + " et scores d'évaluation sur la dernière semaine.")
  @GetMapping("/modele")
  public ModelCard model() {
    return service.model();
  }

  @Operation(
      summary = "Réentraîner le modèle maintenant",
      description = "Sans attendre l'apprentissage de la nuit. Administrateurs.")
  @PostMapping("/entrainement")
  @PreAuthorize("hasRole('ADMIN')")
  public ModelCard train() {
    ModelCard card = service.train();
    audit.record(
        "RISK_MODEL_TRAINED",
        "RISK_MODEL",
        null,
        "echantillons=" + card.samples() + " pannes=" + card.positives());
    return card;
  }
}

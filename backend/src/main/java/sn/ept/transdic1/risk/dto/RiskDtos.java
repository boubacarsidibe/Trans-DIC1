package sn.ept.transdic1.risk.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Échanges de l'API du risque de panne (ADR 0052). */
public final class RiskDtos {
  private RiskDtos() {}

  @Schema(description = "Ce qui pousse le risque d'un équipement vers le haut")
  public record Reason(String feature, String label, String fact, double contribution) {}

  @Schema(
      description =
          "Risque de panne d'un équipement dans les 24 heures. level : FAIBLE, MOYEN, ELEVE")
  public record EquipmentRisk(
      UUID equipmentId,
      String name,
      String siteName,
      double probability,
      String level,
      List<Reason> reasons) {}

  @Schema(description = "Poids d'une variable : appris, et initial (avant apprentissage)")
  public record FeatureWeight(
      String name, String label, double weight, double priorWeight, double oddsRatio) {}

  @Schema(
      description =
          "Fiche du modèle. mode : POIDS_INITIAUX (aucune panne observée), MELANGE (poids appris"
              + " tirés vers les poids initiaux), APPRIS (l'expérience domine)")
  public record ModelCard(
      Instant trainedAt,
      String mode,
      int samples,
      int positives,
      double baseRate,
      Double auc,
      Double brier,
      int evaluationSamples,
      double intercept,
      List<FeatureWeight> features,
      String method) {}

  public record RiskOverview(ModelCard model, List<EquipmentRisk> equipments, Instant computedAt) {}
}

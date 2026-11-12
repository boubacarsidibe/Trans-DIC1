package sn.ept.transdic1.alerting.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import sn.ept.transdic1.alerting.domain.ComparisonOperator;

/**
 * Seuils proposés d'après l'historique (M14, ADR 0035) : ce que la machine fait d'habitude, trois
 * seuils possibles, et le nombre d'alertes que chacun aurait ouvertes sur la même période.
 */
public final class ThresholdAdviceDtos {
  private ThresholdAdviceDtos() {}

  /** Sur quel indicateur, pour quelle portée, et sur combien de jours regarder. */
  public record ThresholdAdviceRequest(
      @NotBlank @Size(max = 64) String metricType,
      @NotNull ComparisonOperator operator,
      @Size(max = 24) String unit,
      @NotNull @Min(1) @Max(14) Integer days,
      @NotNull @Min(0) @Max(86_400) Long confirmationSeconds,
      List<UUID> siteIds,
      List<UUID> equipmentIds,
      Boolean allEquipment) {}

  /** Ce que l'historique contient. */
  public record Distribution(
      int samples,
      int equipments,
      BigDecimal minimum,
      BigDecimal median,
      BigDecimal p90,
      BigDecimal p95,
      BigDecimal p99,
      BigDecimal maximum) {}

  /**
   * Un seuil proposé et ce qu'il aurait donné.
   *
   * @param alertsOverHistory alertes qui se seraient ouvertes sur la période examinée
   * @param equipmentsAffected équipements qui en auraient eu au moins une
   */
  public record Proposal(
      String label,
      String rationale,
      int percentile,
      BigDecimal threshold,
      BigDecimal recoveryThreshold,
      int alertsOverHistory,
      int equipmentsAffected) {}

  /**
   * @param advice phrase à montrer quand rien ne peut être proposé (historique trop maigre)
   */
  public record ThresholdAdviceResponse(
      Instant from,
      Instant to,
      String metricType,
      String unit,
      Distribution distribution,
      List<Proposal> proposals,
      String advice) {}
}

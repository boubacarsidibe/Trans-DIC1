package sn.ept.transdic1.alerting.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Test d'une règle sur l'historique avant activation (FEAT-02) : rien n'est enregistré. */
public final class RuleTestDtos {
  private RuleTestDtos() {}

  /** La règle telle qu'elle serait enregistrée, et le nombre de jours d'historique à rejouer. */
  public record RuleTestRequest(
      @NotNull @Valid AlertRuleRequest rule, @NotNull @Min(1) @Max(7) Integer days) {}

  /** Une alerte qui se serait ouverte ; {@code resolvedAt} nul si elle serait encore active. */
  public record SimulatedAlert(Instant openedAt, Instant resolvedAt, BigDecimal peakValue) {}

  public record EquipmentResult(
      UUID equipmentId, String equipmentName, int samples, List<SimulatedAlert> alerts) {}

  public record RuleTestResponse(
      Instant from,
      Instant to,
      int equipmentsEvaluated,
      int samplesEvaluated,
      int alertsOpened,
      List<EquipmentResult> equipments) {}
}

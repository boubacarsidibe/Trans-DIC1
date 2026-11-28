package sn.ept.transdic1.postmortem.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Indicateurs de service du NOC sur une période : délai moyen de prise en charge (MTTA), délai
 * moyen de résolution (MTTR), part des alertes prises en charge dans l'objectif, et temps moyen
 * entre deux pannes (MTBF) des équipements les plus touchés. Durées en minutes.
 */
@Schema(description = "Indicateurs de service (MTTA, MTTR, MTBF) sur une période")
public record ServiceKpiResponse(
    Instant from,
    Instant to,
    int acknowledgeTargetMinutes,
    Figures overall,
    List<Group> bySeverity,
    List<Group> bySite,
    List<Operator> byOperator,
    List<Week> weeks,
    List<EquipmentReliability> equipments,
    boolean truncated,
    String method,
    @Schema(description = "Escalades de niveau (ADR 0059) : taux et délai par niveau de départ")
        Escalations escalations) {

  /**
   * Part des alertes de la période escaladées au moins une fois, et, par niveau de départ (0 = sans
   * niveau), le nombre d'escalades et le délai médian passé à ce niveau avant d'escalader.
   */
  public record Escalations(long escalated, Double ratePercent, List<LevelEscalation> byLevel) {}

  public record LevelEscalation(int fromLevel, long escalations, Double medianMinutes) {}

  @Schema(description = "Chiffres d'un groupe d'alertes ; une moyenne est nulle sans donnée")
  public record Figures(
      long alerts,
      long acknowledged,
      long resolved,
      long resolvedAutomatically,
      Double mttaMinutes,
      Double mttaMedianMinutes,
      Double mttrMinutes,
      Double mttrMedianMinutes,
      Double acknowledgedWithinTargetPercent,
      Double resolvedAutomaticallyPercent) {}

  public record Group(String key, String label, Figures figures) {}

  public record Operator(
      String username, long acknowledged, Double mttaMedianMinutes, long withinTarget) {}

  public record Week(
      LocalDate weekStart, long alerts, Double mttaMedianMinutes, Double mttrMedianMinutes) {}

  @Schema(
      description =
          "Fiabilité d'un équipement : pannes = alertes de perte de collecte ; MTBF = temps en"
              + " service divisé par le nombre de pannes (nul sans panne)")
  public record EquipmentReliability(
      UUID equipmentId,
      String name,
      String siteName,
      long alerts,
      long failures,
      Double downtimeMinutes,
      Double availabilityPercent,
      Double mtbfHours) {}
}

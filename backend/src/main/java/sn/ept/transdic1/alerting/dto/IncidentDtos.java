package sn.ept.transdic1.alerting.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import sn.ept.transdic1.alerting.domain.AlertState;
import sn.ept.transdic1.alerting.domain.AlertType;
import sn.ept.transdic1.alerting.domain.IncidentState;
import sn.ept.transdic1.alerting.domain.Severity;

/** Réponses de l'API des incidents (M10, ADR 0039). */
public final class IncidentDtos {

  private IncidentDtos() {}

  /** Un incident dans la liste. */
  @Schema(description = "Un fait, décrit par plusieurs alertes regroupées")
  public record IncidentResponse(
      UUID id,
      @Schema(description = "Message de l'alerte qui a commencé l'incident") String title,
      @Schema(description = "La plus haute sévérité de ses alertes") Severity severity,
      IncidentState state,
      @Schema(description = "Site où l'incident a commencé ; il ne change plus ensuite")
          UUID siteId,
      String siteName,
      Instant openedAt,
      @Schema(description = "Dernière alerte entrée dans l'incident") Instant lastAlertAt,
      Instant resolvedAt,
      int alertCount,
      int equipmentCount,
      @Schema(description = "Noms des équipements touchés, au plus cinq, par ordre alphabétique")
          List<String> equipmentNames) {}

  /** Une alerte d'un incident, avec la raison de son rattachement. */
  @Schema(description = "Alerte rattachée à un incident")
  public record IncidentAlertResponse(
      UUID alertId,
      AlertType type,
      Severity severity,
      AlertState state,
      UUID equipmentId,
      String equipmentName,
      String message,
      Instant triggeredAt,
      Instant joinedAt,
      @Schema(description = "Règle qui a rapproché cette alerte des autres, en clair")
          String reason,
      @Schema(description = "Vrai pour l'alerte qui a notifié au nom de l'incident")
          boolean announces) {}

  /** Un incident et ses alertes. */
  public record IncidentDetailResponse(
      IncidentResponse incident, List<IncidentAlertResponse> alerts) {}
}

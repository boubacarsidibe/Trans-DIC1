package sn.ept.transdic1.remediation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import sn.ept.transdic1.remediation.domain.RemediationJob;
import sn.ept.transdic1.remediation.domain.RemediationMode;
import sn.ept.transdic1.remediation.domain.RemediationPolicy;

/** Échanges de l'API des actions correctives (ADR 0050). */
public final class RemediationDtos {
  /** Nom d'action : minuscules, chiffres, point, tiret, tiret bas ; 64 caractères au plus. */
  public static final String NAME = "^[a-z0-9][a-z0-9._-]{0,63}$";

  private RemediationDtos() {}

  @Schema(description = "Action corrective d'une règle d'alerte")
  public record PolicyRequest(
      @NotBlank @Pattern(regexp = NAME, message = "Nom d'action invalide") String actionName,
      @NotNull RemediationMode mode,
      @Min(0) @Max(1440) Integer cooldownMinutes,
      Boolean enabled) {}

  public record PolicyResponse(
      UUID ruleId,
      String actionName,
      RemediationMode mode,
      int cooldownMinutes,
      boolean enabled,
      Instant updatedAt,
      String updatedBy) {
    public static PolicyResponse from(RemediationPolicy p) {
      return new PolicyResponse(
          p.getRuleId(),
          p.getActionName(),
          p.getMode(),
          p.getCooldownMinutes(),
          p.isEnabled(),
          p.getUpdatedAt(),
          p.getUpdatedBy());
    }
  }

  @Schema(description = "Action annoncée par un agent")
  public record ActionOption(String name, String description) {}

  @Schema(description = "Action annoncée par au moins un agent actif")
  public record AvailableAction(String name, String description, int equipmentCount) {}

  @Schema(
      description =
          "Exécution d'une action corrective. state : PENDING_APPROVAL, QUEUED, RUNNING,"
              + " SUCCEEDED, FAILED, REJECTED, EXPIRED ; trigger : AUTO ou MANUAL")
  public record JobResponse(
      UUID id,
      UUID alertId,
      UUID equipmentId,
      String actionName,
      String state,
      String trigger,
      String requestedBy,
      String decidedBy,
      Instant createdAt,
      Instant decidedAt,
      Instant pickedAt,
      Instant finishedAt,
      Instant expiresAt,
      Integer exitCode,
      String output,
      Long durationMs) {
    public static JobResponse from(RemediationJob j) {
      return new JobResponse(
          j.getId(),
          j.getAlertId(),
          j.getEquipmentId(),
          j.getActionName(),
          j.getState().name(),
          j.getTrigger().name(),
          j.getRequestedBy(),
          j.getDecidedBy(),
          j.getCreatedAt(),
          j.getDecidedAt(),
          j.getPickedAt(),
          j.getFinishedAt(),
          j.getExpiresAt(),
          j.getExitCode(),
          j.getOutput(),
          j.getDurationMs());
    }
  }

  @Schema(
      description =
          "Actions correctives d'une alerte : possibles, prévue par sa règle, déjà lancées")
  public record AlertRemediation(
      boolean agentActive,
      List<ActionOption> available,
      PolicyResponse policy,
      List<JobResponse> jobs) {}

  public record LaunchRequest(
      @NotBlank @Pattern(regexp = NAME, message = "Nom d'action invalide") String actionName) {}

  // ------------------------------------------------------------------ côté agent

  public record AnnouncedAction(
      @NotBlank @Pattern(regexp = NAME, message = "Nom d'action invalide") String name,
      @Size(max = 200) String description) {}

  @Schema(description = "Passage de l'agent : actions qu'il sait exécuter")
  public record AgentPollRequest(@Valid @Size(max = 50) List<AnnouncedAction> available) {}

  @Schema(description = "Action à exécuter par l'agent, désignée par son seul nom")
  public record AgentJob(UUID id, String action) {}

  public record AgentPollResponse(List<AgentJob> jobs) {}

  @Schema(description = "Compte rendu d'exécution d'une action par l'agent")
  public record AgentResultRequest(
      @NotNull @Min(-1000) @Max(1000) Integer exitCode,
      @Size(max = 20000) String output,
      @Min(0) Long durationMs) {}
}

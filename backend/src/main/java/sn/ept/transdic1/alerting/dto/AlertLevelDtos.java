package sn.ept.transdic1.alerting.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/** Journal d'intervention, escalade et renvoi (ADR 0059). */
public final class AlertLevelDtos {
  private AlertLevelDtos() {}

  /** Cases proposées dans la fenêtre d'escalade, par leur code. */
  public static final List<String> CHECKS =
      List.of(
          "SERVICE_RESTARTED",
          "EQUIPMENT_OR_CABLE_CHECKED",
          "CONFIGURATION_CHECKED",
          "OUT_OF_MY_RIGHTS",
          "ON_SITE_ACCESS_NEEDED");

  /** Note courte, datée et signée. */
  public record NoteRequest(@NotBlank @Size(max = 500) String text) {}

  /**
   * Escalade : cases cochées, une phrase obligatoire, un destinataire facultatif. {@code
   * expectedLevel} est le niveau que l'écran montrait : si l'alerte a changé de niveau entre-temps
   * (double clic, collègue plus rapide), l'escalade est refusée au lieu de monter deux fois.
   */
  public record EscalationRequest(
      @Size(max = 5) List<@NotBlank @Size(max = 40) String> checks,
      @NotBlank @Size(max = 200) String reason,
      UUID recipientId,
      Integer expectedLevel) {}

  /** Renvoi au niveau inférieur avec une consigne courte. */
  public record SendBackRequest(
      @NotBlank @Size(max = 200) String instruction, Integer expectedLevel) {}

  /** Un destinataire possible d'une escalade. */
  public record Candidate(UUID id, String username, String role, Integer supportLevel) {}

  /** Où irait l'escalade, et qui peut la recevoir. */
  public record EscalationTarget(Integer fromLevel, Integer toLevel, List<Candidate> candidates) {}
}

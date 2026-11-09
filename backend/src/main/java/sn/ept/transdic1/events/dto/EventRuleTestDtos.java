package sn.ept.transdic1.events.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import sn.ept.transdic1.events.domain.EventSeverity;
import sn.ept.transdic1.events.domain.EventSource;

/** Essai d'une règle d'événement sur un exemple, sans rien enregistrer. */
public final class EventRuleTestDtos {
  private EventRuleTestDtos() {}

  /**
   * La règle telle qu'elle serait enregistrée et un événement d'exemple : source, sévérité, OID de
   * trap (facultatif) et texte (message syslog ou variables du trap).
   */
  public record EventRuleTestRequest(
      @NotNull(message = "La règle est obligatoire") @Valid EventRuleRequest rule,
      @NotNull(message = "La source de l'exemple est obligatoire") EventSource source,
      @NotNull(message = "La sévérité de l'exemple est obligatoire") EventSeverity severity,
      @Size(max = 255) String trapOid,
      @Size(max = 4096) String text) {}

  /** {@code opens} : l'exemple ouvrirait une alerte ; {@code resolves} : il la résoudrait. */
  public record EventRuleTestResponse(
      boolean opens, boolean resolves, boolean aborted, String explanation) {}
}

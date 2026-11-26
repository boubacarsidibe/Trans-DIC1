package sn.ept.transdic1.inventory.domain;

/** Étapes d'une demande d'action sur une VM par l'hyperviseur (ADR 0057). */
public enum HypervisorActionState {
  /** Attend la décision d'un administrateur. */
  PENDING_APPROVAL,
  /** Approuvée, et l'hyperviseur a accepté l'ordre. */
  SUCCEEDED,
  /** Approuvée, mais l'hyperviseur a refusé l'ordre ou n'a pas répondu. */
  FAILED,
  REJECTED,
  /** Personne n'a décidé à temps. */
  EXPIRED
}

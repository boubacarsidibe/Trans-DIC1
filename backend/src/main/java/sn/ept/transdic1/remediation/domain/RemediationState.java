package sn.ept.transdic1.remediation.domain;

/**
 * Cycle d'une exécution : en attente d'approbation, en file (l'agent la prendra à son prochain
 * passage), en cours, puis réussie ou en échec ; refusée par un opérateur, ou expirée si l'agent ne
 * l'a pas prise à temps.
 */
public enum RemediationState {
  PENDING_APPROVAL,
  QUEUED,
  RUNNING,
  SUCCEEDED,
  FAILED,
  REJECTED,
  EXPIRED;

  public boolean active() {
    return this == PENDING_APPROVAL || this == QUEUED || this == RUNNING;
  }
}

package sn.ept.transdic1.remediation.domain;

/** Comment une règle déclenche son action corrective. */
public enum RemediationMode {
  /** L'action part dès l'ouverture de l'alerte. */
  AUTO,
  /** L'action attend qu'un opérateur l'approuve. */
  APPROVAL
}

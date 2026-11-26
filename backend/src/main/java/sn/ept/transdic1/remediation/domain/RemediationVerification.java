package sn.ept.transdic1.remediation.domain;

/** Verdict de la vérification après une action corrective (ADR 0057). */
public enum RemediationVerification {
  /** L'alerte était résolue à l'échéance. */
  EFFECTIVE,
  /** L'alerte était toujours ouverte à l'échéance, ou l'action a échoué. */
  INEFFECTIVE
}

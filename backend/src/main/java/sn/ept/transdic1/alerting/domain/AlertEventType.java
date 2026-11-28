package sn.ept.transdic1.alerting.domain;

public enum AlertEventType {
  OPENED,
  TAKEN,
  ASSIGNED,
  UNASSIGNED,
  SEVERITY_CHANGED,
  COMMENTED,
  NOTIFIED,
  RESOLVED,
  ARCHIVED,
  DELETED,
  ESCALATED,
  CORRELATED,
  /** Note datée et signée du journal d'intervention (ADR 0059). */
  NOTE,
  /** Escalade manuelle au niveau supérieur, avec cases cochées, phrase et destinataires. */
  LEVEL_ESCALATED,
  /** Renvoi au niveau inférieur, avec une consigne. */
  SENT_BACK,
  /** Entrées automatiques du journal : diagnostic consulté, cause confirmée, action corrective. */
  DIAGNOSTIC_VIEWED,
  CAUSE_CONFIRMED,
  REMEDIATION
}

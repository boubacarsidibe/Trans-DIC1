package sn.ept.transdic1.inventory.domain;

/** Cycle d'une machine qui s'inscrit (ADR 0054). */
public enum EnrollmentStatus {
  /** En attente d'un administrateur (mode APPROVAL). */
  PENDING,
  /** Approuvée : l'équipement existe, l'agent viendra chercher son jeton au prochain passage. */
  APPROVED,
  /** Refusée par un administrateur : l'agent reçoit 403 tant qu'on ne revient pas dessus. */
  REJECTED,
  /** Inscrite : l'agent a reçu son jeton personnel. */
  ENROLLED
}

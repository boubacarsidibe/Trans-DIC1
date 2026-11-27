package sn.ept.transdic1.threat.domain;

/** Jugement d'un constat de l'EDR (ADR 0058). */
public enum EndpointFindingState {
  /** À examiner. */
  OPEN,
  /** Changement voulu (installation, mise à jour) : il ne reviendra plus. */
  LEGITIMATE,
  /** Confirmé comme une attaque : il reste visible pour l'enquête. */
  MALICIOUS
}

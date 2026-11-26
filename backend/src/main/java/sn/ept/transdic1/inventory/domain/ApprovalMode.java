package sn.ept.transdic1.inventory.domain;

/** Ce que devient une demande d'inscription (ADR 0054). */
public enum ApprovalMode {
  /** L'équipement et l'agent sont créés aussitôt, le jeton personnel part dans la réponse. */
  AUTO,
  /** La demande attend qu'un administrateur l'approuve ; l'agent redemande plus tard. */
  APPROVAL
}

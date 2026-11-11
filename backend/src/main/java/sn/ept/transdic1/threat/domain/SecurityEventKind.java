package sn.ept.transdic1.threat.domain;

/** Nature d'une ligne de journal retenue par la supervision de sécurité. */
public enum SecurityEventKind {
  /** Tentative d'authentification refusée (SSH, connexion web). */
  AUTH_FAILURE,
  /** Authentification réussie : gardée pour reconnaître une intrusion après des échecs. */
  AUTH_SUCCESS,
  /** Requête web dans laquelle une signature d'attaque a été reconnue. */
  WEB_ATTACK,
  /** Requête web en erreur (4xx ou 5xx) sans signature : utile pour voir un balayage. */
  WEB_ERROR
}

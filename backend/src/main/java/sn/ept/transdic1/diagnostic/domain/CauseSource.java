package sn.ept.transdic1.diagnostic.domain;

/**
 * Origine d'une cause probable dans le diagnostic d'incident (ADR 0024). L'ordre de déclaration
 * départage deux causes de même score : la plus structurelle (topologie) passe devant.
 */
public enum CauseSource {
  /** Un équipement en amont (topologie) a une alerte active : cause racine par la topologie. */
  AMONT_EN_ALERTE,
  /** Trap ou message syslog reçu juste avant (linkDown, redémarrage, erreur, échec d'auth). */
  EVENEMENT_RECENT,
  /** Modification tracée dans l'audit juste avant (règle, modèle, maintenance, sonde...). */
  CHANGEMENT_RECENT,
  /** L'équipement ne remonte plus de mesures (agent, SNMP ou ICMP). */
  SILENCE_COLLECTE,
  /** Tendance de saturation (prévision F1.1) sur l'équipement. */
  TENDANCE_SATURATION,
  /** Comportement inhabituel détecté sur un indicateur de l'équipement. */
  ANOMALIE
}

package sn.ept.transdic1.alerting.domain;

/** Vie d'une corrélation apprise (M9, ADR 0042) : proposée, puis acceptée ou écartée. */
public enum LearnedCorrelationState {
  /** La statistique l'a trouvée ; elle ne sert encore à rien. */
  PROPOSED,

  /** Un administrateur l'a validée : elle rapproche désormais les alertes en incidents. */
  ACCEPTED,

  /** Un administrateur l'a écartée : elle ne sera plus reproposée. */
  REJECTED
}

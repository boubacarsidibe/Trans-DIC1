package sn.ept.transdic1.alerting.domain;

/** Issue d'une prévision de saturation (ADR 0020). */
public enum ForecastStatus {
  /** Tendance à la hausse : une date de saturation est estimée. */
  OK,
  /** Pas assez de mesures (période ou nombre de points insuffisant). */
  NOT_ENOUGH_DATA,
  /** Série plate ou en baisse : pas de saturation prévisible. */
  NO_GROWTH,
  /** La valeur actuelle atteint déjà la limite. */
  ALREADY_FULL
}

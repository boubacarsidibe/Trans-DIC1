package sn.ept.transdic1.alerting.domain;

/** Comment une prévision de saturation a été calculée (ADR 0020 et 0036). */
public enum ForecastMethod {
  /** Droite des moindres carrés : une tendance, sans cycle. */
  LINEAR,
  /** Lissage exponentiel à saisonnalité journalière (Holt-Winters). */
  SEASONAL
}

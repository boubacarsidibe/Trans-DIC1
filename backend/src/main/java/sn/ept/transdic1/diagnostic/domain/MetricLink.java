package sn.ept.transdic1.diagnostic.domain;

/**
 * Ce qui a fait retenir un indicateur parmi ceux liés à une alerte (M11, ADR 0040). Les deux
 * raisons ne disent pas la même chose, et l'écran ne doit pas les confondre.
 */
public enum MetricLink {
  /** Son niveau moyen a nettement changé à l'ouverture : une marche d'escalier. */
  CHANGEMENT,

  /** Il varie comme la mesure en alerte, qu'il ait changé de niveau ou non. */
  CORRELATION,

  /** Les deux : il a changé de niveau, et il suit la mesure en alerte. */
  LES_DEUX
}

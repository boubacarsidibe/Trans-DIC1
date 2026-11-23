package sn.ept.transdic1.alerting.domain;

/**
 * État d'un incident (M10, ADR 0039). Volontairement réduit à deux valeurs : un incident est un
 * regroupement, pas un second atelier de travail. La prise en charge, l'assignation et la
 * résolution restent celles des alertes ({@link AlertState}) ; en dédoubler le cycle de vie ici
 * créerait deux vérités concurrentes sur l'état d'une panne.
 */
public enum IncidentState {
  /** Au moins une alerte du groupe est encore active. */
  OPEN,

  /** Toutes les alertes du groupe sont résolues, archivées ou supprimées. */
  RESOLVED
}

package sn.ept.transdic1.alerting.domain;

/** Demande de maintenance d'un opérateur : en attente, validée ou refusée par un administrateur. */
public enum MaintenanceDemandState {
  PENDING,
  APPROVED,
  REFUSED
}

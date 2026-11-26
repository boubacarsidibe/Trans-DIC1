package sn.ept.transdic1.inventory.domain;

/**
 * État d'alimentation vu par l'hyperviseur : RUNNING, STOPPED ou PAUSED pour une VM, ONLINE ou
 * OFFLINE pour un hôte, UNKNOWN quand l'hyperviseur ne sait pas (hôte injoignable, par exemple).
 */
public enum PowerState {
  RUNNING,
  STOPPED,
  PAUSED,
  ONLINE,
  OFFLINE,
  UNKNOWN
}

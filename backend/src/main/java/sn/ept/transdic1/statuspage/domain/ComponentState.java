package sn.ept.transdic1.statuspage.domain;

/**
 * État courant d'un composant (ADR 0026). {@link #rank()} ordonne les états de panne du meilleur au
 * pire ; la maintenance et l'inconnu ne sont pas des pannes (rang 0, à part).
 */
public enum ComponentState {
  OPERATIONAL(0),
  DEGRADED(1),
  PARTIAL_OUTAGE(2),
  MAJOR_OUTAGE(3),
  MAINTENANCE(0),
  UNKNOWN(0);

  private final int rank;

  ComponentState(int rank) {
    this.rank = rank;
  }

  /** Gravité de panne : 0 pour un état sans panne, 3 pour une panne majeure. */
  public int rank() {
    return rank;
  }

  /** Vrai pour les états de fonctionnement mesurés (opérationnel à panne majeure). */
  public boolean measured() {
    return this != MAINTENANCE && this != UNKNOWN;
  }

  /** Le pire de deux états mesurés. */
  public static ComponentState worst(ComponentState a, ComponentState b) {
    return b.rank > a.rank ? b : a;
  }
}

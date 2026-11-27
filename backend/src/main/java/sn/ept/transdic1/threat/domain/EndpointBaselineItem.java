package sn.ept.transdic1.threat.domain;

/**
 * Un élément de la ligne de base d'un poste (ADR 0058) : sa catégorie, une clé stable et une
 * empreinte ({@code null} quand l'élément n'a pas de contenu lisible).
 */
public record EndpointBaselineItem(Category category, String key, String fingerprint) {

  /** Catégories retenues dans la ligne de base. */
  public enum Category {
    PERSISTENCE,
    ACCOUNT,
    INTEGRITY
  }
}

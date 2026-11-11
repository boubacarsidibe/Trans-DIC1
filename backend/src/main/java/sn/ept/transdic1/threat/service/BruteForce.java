package sn.ept.transdic1.threat.service;

/**
 * Décision « force brute » ou « balayage » à partir d'un simple comptage par adresse dans une
 * fenêtre de temps, comme le ferait un seau percé : dix échecs d'authentification d'une même
 * adresse en cinq minutes ne sont pas une faute de frappe.
 *
 * <p>Volontairement sans état : le comptage est fait par la base, qui garde déjà les événements. La
 * décision se rejoue donc à l'identique sur l'historique, et redémarrer le serveur ne remet aucun
 * compteur à zéro.
 */
public final class BruteForce {
  private BruteForce() {}

  /** Vrai si le nombre d'échecs dans la fenêtre atteint le seuil. */
  public static boolean isAttack(int failuresInWindow, int threshold) {
    return failuresInWindow >= threshold;
  }

  /**
   * Phrase d'alerte pour une suite d'échecs d'authentification.
   *
   * @param accounts nombre de comptes différents visés : plusieurs comptes depuis une seule
   *     adresse, c'est un balayage de noms, pas un mot de passe oublié
   */
  public static String explainAuth(
      String address, int failures, int accounts, int windowMinutes, String equipment) {
    String target = accounts > 1 ? accounts + " comptes différents" : "le même compte";
    return "Tentatives d'authentification répétées depuis "
        + address
        + " sur "
        + equipment
        + " : "
        + failures
        + " échecs en "
        + windowMinutes
        + " min, sur "
        + target;
  }

  /** Phrase d'alerte pour un balayage de chemins web (beaucoup d'erreurs depuis une adresse). */
  public static String explainScan(
      String address, int errors, int windowMinutes, String equipment) {
    return "Balayage web depuis "
        + address
        + " sur "
        + equipment
        + " : "
        + errors
        + " requêtes en erreur en "
        + windowMinutes
        + " min";
  }
}

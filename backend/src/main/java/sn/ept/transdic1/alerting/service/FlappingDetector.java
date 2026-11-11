package sn.ept.transdic1.alerting.service;

/**
 * Alerte instable (« clignotement ») : une alerte qui s'ouvre et se referme sans arrêt réveille
 * l'équipe pour rien. Au-delà d'un nombre d'ouvertures dans une fenêtre de temps, l'alerte est
 * déclarée instable : elle reste visible et suit son cycle de vie, mais ne notifie plus, jusqu'à ce
 * qu'elle se calme.
 *
 * <p>Le calcul est volontairement sans état : on compte les ouvertures enregistrées dans la
 * fenêtre. Rien n'est deviné, et la décision se rejoue à l'identique sur l'historique.
 */
public final class FlappingDetector {
  private FlappingDetector() {}

  /**
   * Décide si une alerte est instable au moment où elle s'ouvre de nouveau.
   *
   * @param openingsInWindow ouvertures déjà enregistrées dans la fenêtre, celle-ci comprise
   * @param threshold nombre d'ouvertures à partir duquel l'alerte est tenue pour instable
   */
  public static boolean isFlapping(int openingsInWindow, int threshold) {
    return openingsInWindow >= threshold;
  }

  /**
   * Une alerte instable redevient stable quand il reste assez peu d'ouvertures dans la fenêtre. Le
   * retour exige d'être nettement sous le seuil (la moitié, au moins une ouverture de marge), pour
   * qu'elle n'alterne pas entre instable et stable à chaque passage.
   */
  public static boolean isCalmAgain(int openingsInWindow, int threshold) {
    return openingsInWindow <= calmThreshold(threshold);
  }

  static int calmThreshold(int threshold) {
    return Math.max(1, Math.min(threshold / 2, threshold - 1));
  }

  /** Phrase mise dans la chronologie de l'alerte et dans son message, en français. */
  public static String explain(int openingsInWindow, int windowMinutes) {
    return "Alerte instable : "
        + openingsInWindow
        + " ouvertures en "
        + windowMinutes
        + " min, notifications suspendues jusqu'au retour au calme";
  }
}

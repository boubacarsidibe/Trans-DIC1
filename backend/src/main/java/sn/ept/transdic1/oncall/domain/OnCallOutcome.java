package sn.ept.transdic1.oncall.domain;

/** Résultat d'une étape de la chaîne d'escalade d'astreinte pour une alerte. */
public enum OnCallOutcome {
  /** La personne d'astreinte a été mise dans la file d'envoi, sur son canal. */
  NOTIFIED,
  /**
   * La personne d'astreinte n'a aucun canal de notification actif : signalé, la chaîne continue.
   */
  NO_CHANNEL,
  /** La personne d'astreinte ne voit pas le site de l'alerte (ADR 0018) : signalé, rien envoyé. */
  OUT_OF_SCOPE,
  /** Personne d'astreinte à ce niveau à cet instant. */
  NOBODY
}

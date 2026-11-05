package sn.ept.transdic1.reporting.service;

/** Issue de la mise en file d'un message d'astreinte pour une personne (ADR 0025). */
public enum OnCallDelivery {
  /** Message mis dans la file d'envoi (ou déjà présent : idempotent). */
  QUEUED,
  /** Aucune préférence de notification active, ou destination vide : rien n'est envoyé. */
  NO_CHANNEL,
  /** La personne ne voit pas le site de l'alerte (ADR 0018) : rien n'est envoyé. */
  OUT_OF_SCOPE
}

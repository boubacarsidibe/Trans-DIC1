package sn.ept.transdic1.alerting.service;

import java.util.UUID;
import sn.ept.transdic1.alerting.domain.AlertEventType;

/**
 * Changement d'état produit par l'évaluation d'une mesure. {@code notifyRecipients} est faux
 * pendant une maintenance : l'alerte est tracée, mais personne n'est dérangé.
 */
public record AlertChange(UUID alertId, AlertEventType type, boolean notifyRecipients) {}

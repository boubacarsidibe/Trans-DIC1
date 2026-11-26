package sn.ept.transdic1.common.alerting;

import java.util.UUID;

/**
 * Une action corrective ne suffit plus (ADR 0057) : correction inefficace à l'échéance de sa
 * vérification, ou approbation imposée par la limite de fréquence. Publié par la remédiation,
 * écouté par l'astreinte qui prévient tout de suite, sans dépendance entre les deux modules.
 *
 * @param factKey identifie le fait (une exécution), pour ne prévenir qu'une fois
 * @param reason phrase lisible, reprise dans la chronologie de l'alerte
 */
public record RemediationNeedsHuman(UUID alertId, String factKey, String reason) {}

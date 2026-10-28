package sn.ept.transdic1.alerting.domain;

import java.util.UUID;

/**
 * Publié à chaque changement d'une alerte (ouverture, résolution, prise en charge, assignation,
 * archivage, suppression), notification ou non. Sert à rafraîchir les écrans ouverts en direct :
 * contrairement à {@link AlertChangedEvent}, il n'est jamais supprimé par une maintenance.
 */
public record AlertActivityEvent(UUID alertId, AlertEventType type) {}

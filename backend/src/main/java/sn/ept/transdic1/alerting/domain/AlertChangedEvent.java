package sn.ept.transdic1.alerting.domain;

import java.util.UUID;

/**
 * Publié quand une alerte s'ouvre ou se résout automatiquement, et que quelqu'un doit en être
 * informé (pas pendant une maintenance). Le module reporting l'écoute pour préparer les e-mails :
 * alerting ne connaît pas les notifications.
 */
public record AlertChangedEvent(UUID eventId, UUID alertId, AlertEventType type) {
  /** Chaque événement porte son identifiant : c'est la clé d'idempotence des notifications. */
  public AlertChangedEvent(UUID alertId, AlertEventType type) {
    this(UUID.randomUUID(), alertId, type);
  }
}

package sn.ept.transdic1.overview.broker;

import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import sn.ept.transdic1.alerting.domain.AlertActivityEvent;

/**
 * Écrit les événements à relayer dans la table d'envoi, dans la transaction qui les produit : si la
 * transaction est annulée, l'événement disparaît avec elle ; si elle est validée, il sera publié
 * même si le relais est momentanément arrêté. Le contenu suit un contrat versionné (v1) ; il ne
 * transporte aucune donnée d'alerte, seulement le signal et son type (les écrans rechargent par
 * l'API, qui applique les droits).
 */
@Component
@ConditionalOnProperty(name = "broker.enabled", havingValue = "true")
public class EventOutboxWriter {
  static final String ALERT_ACTIVITY = "AlertActivity";

  private final JdbcTemplate jdbc;

  public EventOutboxWriter(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @EventListener
  public void onAlertActivity(AlertActivityEvent event) {
    UUID eventId = UUID.randomUUID();
    String payload =
        "{\"version\":1,\"eventId\":\""
            + eventId
            + "\",\"alertId\":\""
            + event.alertId()
            + "\",\"type\":\""
            + event.type().name()
            + "\"}";
    jdbc.update(
        "INSERT INTO event_outbox(event_id, type, version, payload) VALUES (?, ?, 1, ?)",
        eventId,
        ALERT_ACTIVITY,
        payload);
  }
}

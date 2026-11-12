package sn.ept.transdic1.threat.service;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import sn.ept.transdic1.alerting.domain.Alert;
import sn.ept.transdic1.alerting.domain.AlertActivityEvent;
import sn.ept.transdic1.alerting.domain.AlertChangedEvent;
import sn.ept.transdic1.alerting.domain.AlertEvent;
import sn.ept.transdic1.alerting.domain.AlertEventType;
import sn.ept.transdic1.alerting.domain.AlertState;
import sn.ept.transdic1.alerting.domain.AlertType;
import sn.ept.transdic1.alerting.domain.Severity;
import sn.ept.transdic1.alerting.repository.AlertEventRepository;
import sn.ept.transdic1.alerting.repository.AlertRepository;
import sn.ept.transdic1.inventory.domain.Equipment;

/**
 * Ouverture des alertes de sécurité, commune à ce qui se juge à l'arrivée d'un lot de journaux
 * ({@link SecurityIngestionService}) et à ce qui se juge heure par heure ({@link
 * SecurityBehaviourService}).
 *
 * <p>Une seule alerte reste ouverte par sujet : tant qu'elle l'est, une attaque qui continue met
 * son message à jour au lieu d'empiler les alertes. Le sujet est désigné par une clé stable —
 * nature, équipement, puis adresse ou compte — à laquelle l'instant d'ouverture est ajouté pour que
 * deux alertes successives sur le même sujet restent distinctes dans l'historique.
 */
@Component
public class SecurityAlerts {
  private static final Set<AlertState> ACTIVE = Set.of(AlertState.OPEN, AlertState.IN_PROGRESS);

  /** Natures d'alerte de sécurité : le préfixe de la clé d'occurrence. */
  public enum Subject {
    /** Requête reconnue comme attaque par une signature. */
    ATTACK("security:attack:"),
    /** Échecs d'authentification répétés depuis une adresse. */
    AUTH("security:auth:"),
    /** Rafale de requêtes en erreur depuis une adresse. */
    SCAN("security:scan:"),
    /** Requêtes de forme inhabituelle repérées par le modèle appris. */
    SUSPECT("security:suspect:"),
    /** Connexion réussie d'un compte depuis une adresse d'où il ne s'est jamais connecté. */
    LOGIN("security:login:"),
    /** Volume d'événements d'une heure très éloigné de l'habitude du même créneau. */
    VOLUME("security:volume:"),
    /** Balayage d'adresses ou de ports repéré dans les flux réseau. */
    NETWORK_SCAN("security:scan-reseau:"),
    /** Inondation d'une destination par de nombreuses sources. */
    FLOOD("security:inondation:");

    private final String prefix;

    Subject(String prefix) {
      this.prefix = prefix;
    }

    public String prefix() {
      return prefix;
    }
  }

  private final AlertRepository alerts;
  private final AlertEventRepository alertEvents;
  private final ApplicationEventPublisher publisher;

  public SecurityAlerts(
      AlertRepository alerts,
      AlertEventRepository alertEvents,
      ApplicationEventPublisher publisher) {
    this.alerts = alerts;
    this.alertEvents = alertEvents;
    this.publisher = publisher;
  }

  /**
   * Ouvre l'alerte, ou met son message à jour si elle est déjà ouverte pour ce sujet.
   *
   * @param key ce qui distingue le sujet sur cet équipement : une adresse, un compte, une nature
   * @return 1 si une alerte a été ouverte, 0 si une alerte existante a été mise à jour
   */
  public int open(
      Equipment equipment,
      Subject subject,
      String key,
      Severity severity,
      String message,
      Instant now) {
    String occurrence = subject.prefix() + equipment.getId() + ":" + key + ":";
    Optional<Alert> active =
        alerts.findFirstByOccurrenceKeyStartingWithAndStateIn(occurrence, ACTIVE);
    if (active.isPresent()) {
      active.get().setMessage(message);
      alerts.save(active.get());
      return 0;
    }
    var alert =
        new Alert(
            equipment,
            occurrence + now.toEpochMilli(),
            AlertType.SECURITY,
            AlertState.OPEN,
            severity,
            message,
            now);
    var event = new AlertEvent(alert, AlertEventType.OPENED, now);
    event.setNewState(AlertState.OPEN);
    alerts.save(alert);
    alertEvents.save(event);
    publisher.publishEvent(new AlertChangedEvent(alert.getId(), AlertEventType.OPENED));
    publisher.publishEvent(new AlertActivityEvent(alert.getId(), AlertEventType.OPENED));
    return 1;
  }
}

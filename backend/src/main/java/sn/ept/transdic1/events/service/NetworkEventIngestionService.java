package sn.ept.transdic1.events.service;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.alerting.service.EventAlertService;
import sn.ept.transdic1.events.domain.EventRule;
import sn.ept.transdic1.events.domain.EventSource;
import sn.ept.transdic1.events.domain.EventText;
import sn.ept.transdic1.events.domain.NetworkEvent;
import sn.ept.transdic1.events.receiver.ReceivedEvent;
import sn.ept.transdic1.events.repository.NetworkEventRepository;
import sn.ept.transdic1.events.service.EventSourceDirectory.SourceEquipment;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;

/**
 * Traitement d'un événement reçu (ADR 0021) : authentification de la source par son adresse (un
 * équipement supervisé dont l'adresse de gestion, sous forme canonique, est l'adresse IP de
 * l'émetteur), stockage, puis évaluation des règles d'événements actives. Une source inconnue est
 * rejetée et comptée, jamais stockée. Si plusieurs équipements partagent l'adresse, l'événement est
 * rattaché à chacun.
 *
 * <p>Sources et règles viennent de caches ({@link EventSourceDirectory}, {@link ActiveEventRules})
 * : en régime établi, un événement ne coûte que ses écritures, sans requête de lecture d'inventaire
 * ni de règles.
 */
@Service
public class NetworkEventIngestionService {
  private static final Logger LOG = LoggerFactory.getLogger(NetworkEventIngestionService.class);

  private final EquipmentRepository equipments;
  private final NetworkEventRepository events;
  private final EventSourceDirectory directory;
  private final ActiveEventRules activeRules;
  private final EventRuleMatcher matcher;
  private final EventAlertService eventAlerts;
  private final EventMetrics metrics;
  private final Clock clock;
  private final WarningThrottle abortWarnings = new WarningThrottle(60_000);

  public NetworkEventIngestionService(
      EquipmentRepository equipments,
      NetworkEventRepository events,
      EventSourceDirectory directory,
      ActiveEventRules activeRules,
      EventRuleMatcher matcher,
      EventAlertService eventAlerts,
      EventMetrics metrics,
      Clock clock) {
    this.equipments = equipments;
    this.events = events;
    this.directory = directory;
    this.activeRules = activeRules;
    this.matcher = matcher;
    this.eventAlerts = eventAlerts;
    this.metrics = metrics;
    this.clock = clock;
  }

  /** Stocke l'événement pour chaque équipement de la source ; retourne le nombre stocké. */
  @Transactional
  public int ingest(ReceivedEvent received) {
    List<SourceEquipment> targets = directory.lookup(received.sourceAddress());
    if (targets.isEmpty()) {
      metrics.dropped("unknown_source");
      LOG.debug("Événement ignoré : source inconnue {}", received.sourceAddress());
      return 0;
    }
    List<EventRule> active = activeRules.get();
    long[] budget = {SafeRegex.EVENT_BUDGET};
    for (SourceEquipment target : targets) {
      // Référence sans lecture : l'identifiant suffit pour les clés étrangères.
      Equipment equipment = equipments.getReferenceById(target.equipmentId());
      store(equipment, received);
      metrics.stored(received.source());
      evaluate(equipment, target, received, active, budget);
    }
    return targets.size();
  }

  private void store(Equipment equipment, ReceivedEvent received) {
    var event =
        new NetworkEvent(
            equipment,
            received.source(),
            received.severity(),
            EventText.clean(received.sourceAddress(), 64),
            title(received),
            EventText.cleanOrNull(received.details(), EventText.MAX_DETAILS),
            EventText.cleanOrNull(received.trapOid(), 255),
            received.receivedAt());
    event.setHostname(EventText.cleanOrNull(received.hostname(), 255));
    event.setAppName(EventText.cleanOrNull(received.appName(), 64));
    event.setFacility(received.facility() == null ? null : received.facility().shortValue());
    events.save(event);
  }

  private static String title(ReceivedEvent received) {
    String title = EventText.cleanOrNull(received.title(), EventText.MAX_TITLE);
    return title == null ? "(message vide)" : title;
  }

  private void evaluate(
      Equipment equipment,
      SourceEquipment target,
      ReceivedEvent received,
      List<EventRule> active,
      long[] budget) {
    var facts =
        new EventFacts(
            received.source(),
            received.severity(),
            received.trapOid(),
            text(received),
            target.equipmentId(),
            target.siteId());
    for (EventRule rule : active) {
      var verdict = matcher.evaluate(rule, facts, budget);
      if (verdict.aborted()) {
        metrics.regexAborted();
        if (abortWarnings.allow(rule.getId(), clock.millis())) {
          LOG.warn(
              "Règle d'événement « {} » : expression interrompue (trop coûteuse) ; avertissement"
                  + " limité à un par minute et par règle",
              WarningThrottle.loggable(rule.getName()));
        }
      }
      switch (verdict.decision()) {
        case OPEN ->
            eventAlerts.raise(
                equipment,
                target.siteId(),
                rule.getId(),
                rule.getAlertSeverity(),
                message(rule, received),
                Duration.ofSeconds(rule.getDedupWindowSeconds()),
                received.receivedAt());
        case RESOLVE ->
            eventAlerts.clear(
                equipment,
                rule.getId(),
                EventText.clean("Événement de retour reçu : " + title(received), 1000),
                received.receivedAt());
        case NONE -> {
          // Rien à faire.
        }
      }
    }
  }

  /** Texte lu par les expressions : message syslog, ou titre et variables du trap. */
  static String text(ReceivedEvent received) {
    String details = received.details() == null ? "" : received.details();
    if (received.source() == EventSource.SNMP_TRAP) {
      return received.title() + " ; " + details;
    }
    return details.isEmpty() ? String.valueOf(received.title()) : details;
  }

  private static String message(EventRule rule, ReceivedEvent received) {
    return EventText.clean(
        "Événement réseau « " + rule.getName() + " » : " + title(received), 2000);
  }
}

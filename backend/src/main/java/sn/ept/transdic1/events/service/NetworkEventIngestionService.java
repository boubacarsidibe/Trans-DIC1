package sn.ept.transdic1.events.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.alerting.service.EventAlertService;
import sn.ept.transdic1.events.domain.EventRule;
import sn.ept.transdic1.events.domain.EventSource;
import sn.ept.transdic1.events.domain.EventText;
import sn.ept.transdic1.events.domain.NetworkEvent;
import sn.ept.transdic1.events.receiver.ReceivedEvent;
import sn.ept.transdic1.events.repository.EventRuleRepository;
import sn.ept.transdic1.events.repository.NetworkEventRepository;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;

/**
 * Traitement d'un événement reçu (ADR 0021) : authentification de la source par son adresse (un
 * équipement supervisé dont l'adresse de gestion est l'adresse IP de l'émetteur), stockage, puis
 * évaluation des règles d'événements actives. Une source inconnue est rejetée et comptée, jamais
 * stockée. Si plusieurs équipements partagent l'adresse, l'événement est rattaché à chacun.
 */
@Service
public class NetworkEventIngestionService {
  private static final Logger LOG = LoggerFactory.getLogger(NetworkEventIngestionService.class);

  private final EquipmentRepository equipments;
  private final NetworkEventRepository events;
  private final EventRuleRepository rules;
  private final EventRuleMatcher matcher;
  private final EventAlertService eventAlerts;
  private final EventMetrics metrics;
  private final Clock clock;
  private final Duration cacheTtl;

  private volatile Map<String, List<UUID>> addressCache = Map.of();
  private volatile Instant cacheLoadedAt = Instant.MIN;

  public NetworkEventIngestionService(
      EquipmentRepository equipments,
      NetworkEventRepository events,
      EventRuleRepository rules,
      EventRuleMatcher matcher,
      EventAlertService eventAlerts,
      EventMetrics metrics,
      Clock clock,
      @Value("${events.source-cache-seconds:30}") long cacheSeconds) {
    this.equipments = equipments;
    this.events = events;
    this.rules = rules;
    this.matcher = matcher;
    this.eventAlerts = eventAlerts;
    this.metrics = metrics;
    this.clock = clock;
    this.cacheTtl = Duration.ofSeconds(Math.max(0, cacheSeconds));
  }

  /** Stocke l'événement pour chaque équipement de la source ; retourne le nombre stocké. */
  @Transactional
  public int ingest(ReceivedEvent received) {
    List<Equipment> targets = sources(received.sourceAddress());
    if (targets.isEmpty()) {
      metrics.dropped("unknown_source");
      LOG.debug("Événement ignoré : source inconnue {}", received.sourceAddress());
      return 0;
    }
    List<EventRule> active = rules.findByActiveTrue();
    for (Equipment equipment : targets) {
      store(equipment, received);
      metrics.stored(received.source());
      evaluate(equipment, received, active);
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

  private void evaluate(Equipment equipment, ReceivedEvent received, List<EventRule> active) {
    var facts =
        new EventFacts(
            received.source(),
            received.severity(),
            received.trapOid(),
            text(received),
            equipment.getId(),
            equipment.getSite() == null ? null : equipment.getSite().getId());
    for (EventRule rule : active) {
      var verdict = matcher.evaluate(rule, facts);
      if (verdict.aborted()) {
        metrics.regexAborted();
        LOG.warn(
            "Règle d'événement « {} » : expression interrompue (trop coûteuse)", rule.getName());
      }
      switch (verdict.decision()) {
        case OPEN ->
            eventAlerts.raise(
                equipment,
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

  /**
   * Équipements supervisés à cette adresse, par le cache (ou la base si le cache est désactivé).
   */
  private List<Equipment> sources(String address) {
    if (address == null) {
      return List.of();
    }
    String key = address.trim().toLowerCase(Locale.ROOT);
    if (cacheTtl.isZero()) {
      return equipments.findByManagementAddressAndMonitoringEnabledTrue(key);
    }
    Instant now = clock.instant();
    if (Duration.between(cacheLoadedAt, now).compareTo(cacheTtl) >= 0) {
      Map<String, List<UUID>> fresh = new HashMap<>();
      for (Object[] row : equipments.findMonitoredAddresses()) {
        fresh
            .computeIfAbsent(
                ((String) row[0]).trim().toLowerCase(Locale.ROOT), k -> new ArrayList<>())
            .add((UUID) row[1]);
      }
      addressCache = Map.copyOf(fresh);
      cacheLoadedAt = now;
    }
    List<UUID> ids = addressCache.get(key);
    return ids == null ? List.of() : equipments.findAllById(ids);
  }
}

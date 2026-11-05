package sn.ept.transdic1.collection.service;

import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.alerting.service.AlertChange;
import sn.ept.transdic1.alerting.service.CollectionAlertService;
import sn.ept.transdic1.inventory.domain.AgentStatus;
import sn.ept.transdic1.inventory.domain.CollectionMode;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.MonitoredService;
import sn.ept.transdic1.inventory.domain.ServiceType;
import sn.ept.transdic1.inventory.repository.CollectionAgentRepository;
import sn.ept.transdic1.inventory.repository.CollectionConfigurationRepository;
import sn.ept.transdic1.inventory.repository.MonitoredServiceRepository;

/**
 * Joignabilité d'un équipement sans agent actif ni collecte SNMP active, donnée par ses sondes ICMP
 * actives : il est hors service tant qu'au moins une est à {@link #FAILURES_BEFORE_DOWN} échecs
 * consécutifs, et ne repasse en service que lorsqu'aucune ne l'est plus. Ainsi deux sondes, l'une
 * muette et l'autre saine, ne font pas osciller l'alerte de collecte à chaque passage.
 */
@Service
public class IcmpReachabilityService {
  /** Échecs ICMP consécutifs après lesquels un équipement sans autre source passe hors service. */
  public static final int FAILURES_BEFORE_DOWN = 3;

  private final MonitoredServiceRepository services;
  private final CollectionAgentRepository agents;
  private final CollectionConfigurationRepository configurations;
  private final CollectionAlertService collectionAlerts;

  public IcmpReachabilityService(
      MonitoredServiceRepository services,
      CollectionAgentRepository agents,
      CollectionConfigurationRepository configurations,
      CollectionAlertService collectionAlerts) {
    this.services = services;
    this.agents = agents;
    this.configurations = configurations;
    this.collectionAlerts = collectionAlerts;
  }

  /**
   * Sans agent actif ni collecte SNMP active, seules les sondes ICMP renseignent la joignabilité.
   */
  public boolean drivesStatus(Equipment equipment) {
    if (agents.findByEquipmentIdAndStatus(equipment.getId(), AgentStatus.ACTIVE).isPresent()) {
      return false;
    }
    return configurations.findByEquipmentId(equipment.getId()).stream()
        .noneMatch(c -> c.isActive() && c.getMode() == CollectionMode.SNMP);
  }

  /** Vrai si la sonde, telle qu'elle est, retient l'équipement hors service. */
  public static boolean holdsDown(ServiceType type, boolean active, int consecutiveFailures) {
    return type == ServiceType.ICMP && active && consecutiveFailures >= FAILURES_BEFORE_DOWN;
  }

  /**
   * La sonde {@code released} ne retient plus l'équipement (succès, désactivation, suppression,
   * nouvelle cible) : si les sondes ICMP pilotent le statut et qu'aucune autre sonde ICMP active
   * n'est à {@link #FAILURES_BEFORE_DOWN} échecs, l'équipement repasse en service et l'alerte de
   * collecte se résout. Le compte exclut la sonde libérée, quel que soit son état en mémoire.
   */
  @Transactional
  public Optional<AlertChange> release(MonitoredService released, Instant at) {
    Equipment equipment = released.getEquipment();
    if (!drivesStatus(equipment)) {
      return Optional.empty();
    }
    long othersDown =
        services
            .countByEquipmentIdAndTypeAndActiveTrueAndConsecutiveFailuresGreaterThanEqualAndIdNot(
                equipment.getId(), ServiceType.ICMP, FAILURES_BEFORE_DOWN, released.getId());
    if (othersDown > 0) {
      return Optional.empty();
    }
    // markReachable laisse un équipement en maintenance inchangé ; les deux appels sont
    // idempotents.
    equipment.markReachable();
    return collectionAlerts.reportRecovery(equipment, at);
  }

  /**
   * Échec d'une sonde ICMP : au {@link #FAILURES_BEFORE_DOWN}e échec consécutif et au-delà, ouvre
   * l'alerte de collecte (idempotent tant qu'elle est active).
   */
  @Transactional
  public Optional<AlertChange> reportFailure(MonitoredService probe, Instant at) {
    if (probe.getConsecutiveFailures() < FAILURES_BEFORE_DOWN
        || !drivesStatus(probe.getEquipment())) {
      return Optional.empty();
    }
    return collectionAlerts.reportFailure(
        probe.getEquipment(),
        "Équipement injoignable : "
            + probe.getConsecutiveFailures()
            + " échecs consécutifs de la sonde ICMP « "
            + probe.getName()
            + " »",
        at);
  }
}

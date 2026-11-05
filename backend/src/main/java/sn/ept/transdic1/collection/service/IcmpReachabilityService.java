package sn.ept.transdic1.collection.service;

import jakarta.persistence.EntityManager;
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
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.MonitoredServiceRepository;

/**
 * Joignabilité d'un équipement sans agent actif ni collecte SNMP active, donnée par ses sondes ICMP
 * actives : il est hors service tant qu'au moins une est à {@link #FAILURES_BEFORE_DOWN} échecs
 * consécutifs, et ne repasse en service que lorsqu'aucune ne l'est plus. Ainsi deux sondes, l'une
 * muette et l'autre saine, ne font pas osciller l'alerte de collecte à chaque passage.
 *
 * <p>Les sondes d'un même équipement s'exécutent en parallèle, chacune dans sa transaction : chaque
 * décision verrouille d'abord la ligne de l'équipement ({@link #lock}), pour que la seconde attende
 * la première et lise son compteur validé. Sans cela, deux sondes qui reprennent ensemble verraient
 * chacune l'autre encore à 3 échecs et aucune ne rétablirait l'équipement.
 */
@Service
public class IcmpReachabilityService {
  /** Échecs ICMP consécutifs après lesquels un équipement sans autre source passe hors service. */
  public static final int FAILURES_BEFORE_DOWN = 3;

  private final MonitoredServiceRepository services;
  private final CollectionAgentRepository agents;
  private final CollectionConfigurationRepository configurations;
  private final CollectionAlertService collectionAlerts;
  private final EquipmentRepository equipments;
  private final EntityManager em;

  public IcmpReachabilityService(
      MonitoredServiceRepository services,
      CollectionAgentRepository agents,
      CollectionConfigurationRepository configurations,
      CollectionAlertService collectionAlerts,
      EquipmentRepository equipments,
      EntityManager em) {
    this.equipments = equipments;
    this.em = em;
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
  public static boolean holdsDown(MonitoredService probe) {
    return probe.getType() == ServiceType.ICMP
        && probe.isActive()
        && probe.getConsecutiveFailures() >= FAILURES_BEFORE_DOWN;
  }

  /**
   * Verrouille l'équipement jusqu'à la fin de la transaction, puis relit son statut : une autre
   * transaction a pu le changer depuis son chargement. À appeler avant toute écriture qui référence
   * l'équipement (mesure, alerte) : une écriture de ce genre prend un verrou partagé sur sa ligne,
   * et deux transactions qui le prendraient avant ce verrou exclusif se bloqueraient mutuellement.
   * La requête valide d'abord les changements en attente, rien n'est perdu.
   */
  @Transactional
  public void lock(Equipment equipment) {
    equipments.findForUpdate(equipment.getId());
    em.refresh(equipment);
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
    lock(equipment);
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
    if (probe.getConsecutiveFailures() < FAILURES_BEFORE_DOWN) {
      return Optional.empty();
    }
    lock(probe.getEquipment());
    if (!drivesStatus(probe.getEquipment())) {
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

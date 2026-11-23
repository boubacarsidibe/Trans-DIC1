package sn.ept.transdic1.collection.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import sn.ept.transdic1.alerting.service.CollectionAlertService;
import sn.ept.transdic1.common.scheduling.JobGuard;
import sn.ept.transdic1.inventory.domain.AgentStatus;
import sn.ept.transdic1.inventory.domain.CollectionProxy;
import sn.ept.transdic1.inventory.repository.CollectionAgentRepository;
import sn.ept.transdic1.inventory.repository.CollectionProxyRepository;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.ProxyBatchRepository;

/**
 * Surveille le silence des proxys (ADR 0047) : un proxy actif qui n'a rien envoyé depuis trop
 * longtemps ouvre une alerte de collecte sur les équipements du site qu'il relaie.
 *
 * <p>Pourquoi une alerte par équipement et non une seule pour le site : une alerte est attachée à
 * un équipement, et inventer un équipement « site » pour y accrocher la panne aurait menti sur
 * l'inventaire. Le regroupement en incident (ADR 0039) fait le reste — mêmes site et message dans
 * la fenêtre, un seul incident à traiter. Ce sont deux mécanismes qui existaient déjà, et ils
 * suffisent.
 *
 * <p>Un équipement qui a son propre agent actif est laissé de côté : son silence est déjà surveillé
 * par {@link AgentWatchdogService}, et deux alertes pour une panne n'en font pas une plus visible.
 * L'alerte se résout seule à la première mesure reçue, y compris une mesure rejouée du tampon : le
 * tampon qui se vide prouve que la liaison est revenue.
 */
@Service
public class ProxyWatchdogService {
  private static final Logger LOG = LoggerFactory.getLogger(ProxyWatchdogService.class);

  /** Les lots mémorisés ne servent qu'à reconnaître un rejeu immédiat. */
  private static final Duration BATCH_MEMORY = Duration.ofDays(2);

  private final CollectionProxyRepository proxies;
  private final EquipmentRepository equipments;
  private final CollectionAgentRepository agents;
  private final ProxyBatchRepository batches;
  private final CollectionAlertService collectionAlerts;
  private final Clock clock;
  private final Duration silence;
  private final JobGuard guard;
  private final TransactionTemplate transaction;

  public ProxyWatchdogService(
      CollectionProxyRepository proxies,
      EquipmentRepository equipments,
      CollectionAgentRepository agents,
      ProxyBatchRepository batches,
      CollectionAlertService collectionAlerts,
      Clock clock,
      JobGuard guard,
      TransactionTemplate transaction,
      @Value("${collection.proxy.silence-seconds:600}") long silenceSeconds) {
    this.proxies = proxies;
    this.equipments = equipments;
    this.agents = agents;
    this.batches = batches;
    this.collectionAlerts = collectionAlerts;
    this.clock = clock;
    this.silence = Duration.ofSeconds(silenceSeconds);
    this.guard = guard;
    this.transaction = transaction;
  }

  /** Passage planifié : une seule instance à la fois, et le site du proxy se lit en transaction. */
  @Scheduled(cron = "${collection.proxy.watchdog-cron:0 * * * * *}", zone = "UTC")
  public void scheduledCheck() {
    guard.run(
        "collection.proxy-watchdog",
        Duration.ofMinutes(5),
        () ->
            transaction.execute(
                s -> {
                  batches.deleteOlderThan(clock.instant().minus(BATCH_MEMORY));
                  return checkProxies();
                }));
  }

  /** Retourne le nombre d'alertes ouvertes par ce passage. */
  @Transactional
  public int checkProxies() {
    Instant now = clock.instant();
    int opened = 0;
    for (CollectionProxy proxy : proxies.findByStatus(AgentStatus.ACTIVE)) {
      if (Duration.between(proxy.lastSignOfLife(), now).compareTo(silence) <= 0) {
        continue;
      }
      var site = proxy.getSite();
      String message =
          "Proxy de collecte "
              + proxy.getIdentifier()
              + " du site "
              + site.getName()
              + " muet depuis "
              + Duration.between(proxy.lastSignOfLife(), now).toMinutes()
              + " min";
      int openedHere = 0;
      for (var equipment : equipments.findBySiteIdIn(java.util.List.of(site.getId()))) {
        if (!equipment.isMonitoringEnabled()) {
          continue;
        }
        if (agents.findByEquipmentIdAndStatus(equipment.getId(), AgentStatus.ACTIVE).isPresent()) {
          continue;
        }
        if (collectionAlerts.reportFailure(equipment, message, now).isPresent()) {
          openedHere++;
        }
      }
      if (openedHere > 0) {
        LOG.warn("{} : {} alertes de collecte ouvertes", message, openedHere);
      }
      opened += openedHere;
    }
    return opened;
  }
}

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
import sn.ept.transdic1.alerting.service.CollectionAlertService;
import sn.ept.transdic1.common.scheduling.JobGuard;
import sn.ept.transdic1.inventory.domain.AgentStatus;
import sn.ept.transdic1.inventory.domain.CollectionAgent;
import sn.ept.transdic1.inventory.repository.CollectionAgentRepository;

/**
 * Surveille le silence des agents : un agent actif qui n'a rien envoyé depuis trop longtemps ouvre
 * une alerte de collecte sur son équipement. L'alerte se résout seule à la prochaine mesure (voir
 * MeasureIngestionService).
 */
@Service
public class AgentWatchdogService {
  private static final Logger LOG = LoggerFactory.getLogger(AgentWatchdogService.class);

  private final CollectionAgentRepository agents;
  private final CollectionAlertService collectionAlerts;
  private final Clock clock;
  private final Duration silence;
  private final JobGuard guard;

  public AgentWatchdogService(
      CollectionAgentRepository agents,
      CollectionAlertService collectionAlerts,
      Clock clock,
      JobGuard guard,
      @Value("${collection.agent.silence-seconds:300}") long silenceSeconds) {
    this.agents = agents;
    this.collectionAlerts = collectionAlerts;
    this.clock = clock;
    this.silence = Duration.ofSeconds(silenceSeconds);
    this.guard = guard;
  }

  /** Passage planifié : une seule instance à la fois. */
  @Scheduled(cron = "${collection.agent.watchdog-cron:0 * * * * *}", zone = "UTC")
  public void scheduledCheck() {
    guard.run("collection.agent-watchdog", Duration.ofMinutes(5), this::checkAgents);
  }

  /** Retourne le nombre d'alertes ouvertes par ce passage. */
  @Transactional
  public int checkAgents() {
    Instant now = clock.instant();
    int opened = 0;
    for (CollectionAgent agent : agents.findByStatus(AgentStatus.ACTIVE)) {
      var equipment = agent.getEquipment();
      if (!equipment.isMonitoringEnabled()) {
        continue;
      }
      // Un agent tout juste enregistré dispose du même délai pour se manifester.
      Instant lastSign =
          agent.getLastCommunicationAt() != null
              ? agent.getLastCommunicationAt()
              : agent.getRegisteredAt();
      if (Duration.between(lastSign, now).compareTo(silence) <= 0) {
        continue;
      }
      String message =
          "L'agent de "
              + equipment.getName()
              + " ne communique plus depuis "
              + Duration.between(lastSign, now).toMinutes()
              + " min";
      if (collectionAlerts.reportFailure(equipment, message, now).isPresent()) {
        opened++;
        LOG.warn("Agent silencieux : alerte de collecte ouverte pour {}", equipment.getId());
      }
    }
    return opened;
  }
}

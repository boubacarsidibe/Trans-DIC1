package sn.ept.transdic1.events.service;

import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.common.scheduling.JobGuard;
import sn.ept.transdic1.events.repository.NetworkEventRepository;

/**
 * Purge des événements réseau plus anciens que {@code events.retention-days} (30 jours par défaut),
 * une fois par nuit, sous le verrou JobGuard comme la rétention des mesures.
 */
@Service
public class NetworkEventRetentionService {
  private static final Logger LOG = LoggerFactory.getLogger(NetworkEventRetentionService.class);

  private final NetworkEventRepository events;
  private final JobGuard guard;
  private final Clock clock;
  private final Duration retention;

  public NetworkEventRetentionService(
      NetworkEventRepository events,
      JobGuard guard,
      Clock clock,
      @Value("${events.retention-days:30}") long retentionDays) {
    this.events = events;
    this.guard = guard;
    this.clock = clock;
    this.retention = Duration.ofDays(Math.max(1, retentionDays));
  }

  @Scheduled(cron = "${events.retention-cron:0 15 4 * * *}", zone = "UTC")
  public void scheduledPurge() {
    guard.run("events.retention", Duration.ofMinutes(10), this::purgeExpired);
  }

  /** Supprime les événements expirés ; retourne le nombre de lignes supprimées. */
  @Transactional
  public int purgeExpired() {
    int deleted = events.deleteReceivedBefore(clock.instant().minus(retention));
    if (deleted > 0) {
      LOG.info("Événements réseau expirés supprimés : {}", deleted);
    }
    return deleted;
  }
}

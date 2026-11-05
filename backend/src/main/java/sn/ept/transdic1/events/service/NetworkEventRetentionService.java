package sn.ept.transdic1.events.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import sn.ept.transdic1.common.scheduling.JobGuard;
import sn.ept.transdic1.events.repository.NetworkEventRepository;

/**
 * Purge des événements réseau plus anciens que {@code events.retention-days} (30 jours par défaut),
 * une fois par nuit, sous le verrou JobGuard comme la rétention des mesures. La suppression se fait
 * par lots de {@value #BATCH} lignes, chacun dans sa transaction : pas d'énorme DELETE qui
 * verrouille la table et gonfle le journal après une avalanche de syslog.
 */
@Service
public class NetworkEventRetentionService {
  private static final Logger LOG = LoggerFactory.getLogger(NetworkEventRetentionService.class);
  static final int BATCH = 5_000;

  private final NetworkEventRepository events;
  private final JobGuard guard;
  private final Clock clock;
  private final Duration retention;
  private final TransactionTemplate tx;

  public NetworkEventRetentionService(
      NetworkEventRepository events,
      JobGuard guard,
      Clock clock,
      PlatformTransactionManager transactions,
      @Value("${events.retention-days:30}") long retentionDays) {
    this.events = events;
    this.guard = guard;
    this.clock = clock;
    this.tx = new TransactionTemplate(transactions);
    this.retention = Duration.ofDays(Math.max(1, retentionDays));
  }

  @Scheduled(cron = "${events.retention-cron:0 15 4 * * *}", zone = "UTC")
  public void scheduledPurge() {
    guard.run("events.retention", Duration.ofMinutes(10), this::purgeExpired);
  }

  /** Supprime les événements expirés ; retourne le nombre de lignes supprimées. */
  public int purgeExpired() {
    Instant before = clock.instant().minus(retention);
    int total = 0;
    int deleted;
    do {
      Integer batch = tx.execute(status -> events.deleteReceivedBefore(before, BATCH));
      deleted = batch == null ? 0 : batch;
      total += deleted;
    } while (deleted == BATCH);
    if (total > 0) {
      LOG.info("Événements réseau expirés supprimés : {}", total);
    }
    return total;
  }
}

package sn.ept.transdic1.threat.service;

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
import sn.ept.transdic1.threat.repository.SecurityEventRepository;

/**
 * Purge des journaux de sécurité plus anciens que {@code threat.retention-days} (30 jours par
 * défaut), une fois par nuit. Ces lignes portent des données personnelles — adresse de l'appelant,
 * nom de compte visé, URL demandée — : les garder plus longtemps que nécessaire serait une faute,
 * et la durée est réglable par l'école.
 *
 * <p>Suppression par lots, chacun dans sa transaction : après une avalanche d'attaques, un seul
 * grand DELETE verrouillerait la table.
 */
@Service
public class SecurityEventRetentionService {
  private static final Logger LOG = LoggerFactory.getLogger(SecurityEventRetentionService.class);
  static final int BATCH = 5_000;

  private final SecurityEventRepository events;
  private final JobGuard guard;
  private final Clock clock;
  private final TransactionTemplate tx;
  private final Duration retention;

  public SecurityEventRetentionService(
      SecurityEventRepository events,
      JobGuard guard,
      Clock clock,
      PlatformTransactionManager transactions,
      @Value("${threat.retention-days:30}") long retentionDays) {
    this.events = events;
    this.guard = guard;
    this.clock = clock;
    this.tx = new TransactionTemplate(transactions);
    this.retention = Duration.ofDays(Math.max(1, retentionDays));
  }

  @Scheduled(cron = "${threat.retention-cron:0 30 4 * * *}", zone = "UTC")
  public void scheduledPurge() {
    guard.run("threat.retention", Duration.ofMinutes(10), this::purgeExpired);
  }

  /** Supprime les journaux expirés ; retourne le nombre de lignes supprimées. */
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
      LOG.info("Journaux de sécurité purgés : {} ligne(s)", total);
    }
    return total;
  }
}

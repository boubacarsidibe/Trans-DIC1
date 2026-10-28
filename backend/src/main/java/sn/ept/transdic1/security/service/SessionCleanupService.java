package sn.ept.transdic1.security.service;

import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.security.repository.UserSessionRepository;

/**
 * Supprime les sessions de renouvellement expirées depuis un certain temps. Un délai de grâce est
 * conservé pour que rejouer un très vieux jeton reste détectable comme réutilisation.
 */
@Service
public class SessionCleanupService {
  private static final Logger LOG = LoggerFactory.getLogger(SessionCleanupService.class);

  private final UserSessionRepository sessions;
  private final Clock clock;
  private final Duration retention;

  public SessionCleanupService(
      UserSessionRepository sessions,
      Clock clock,
      @Value("${security.refresh.cleanup-retention-days:7}") long retentionDays) {
    this.sessions = sessions;
    this.clock = clock;
    this.retention = Duration.ofDays(retentionDays);
  }

  @Scheduled(cron = "${security.refresh.cleanup-cron:0 30 3 * * *}", zone = "UTC")
  @Transactional
  public int purgeExpiredSessions() {
    int deleted = sessions.deleteExpiredBefore(clock.instant().minus(retention));
    if (deleted > 0) {
      LOG.info("Sessions de renouvellement expirées supprimées : {}", deleted);
    }
    return deleted;
  }
}

package sn.ept.transdic1.overview.broker;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import sn.ept.transdic1.common.scheduling.JobGuard;

/**
 * Relais de la table d'envoi vers le canal PostgreSQL : publie les événements en attente par {@code
 * NOTIFY}, dans l'ordre, puis les marque publiés dans la même transaction (NOTIFY n'est émis qu'à
 * la validation). En cas d'échec, rien n'est marqué et le passage suivant réessaie. Une seule
 * instance relaie à la fois (verrou {@link JobGuard}). Les lignes publiées depuis plus de deux
 * jours sont purgées chaque nuit.
 */
@Component
@ConditionalOnProperty(name = "broker.enabled", havingValue = "true")
public class EventRelay {
  private static final Logger LOG = LoggerFactory.getLogger(EventRelay.class);
  private static final int BATCH = 200;

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transaction;
  private final JobGuard guard;
  private final MeterRegistry meters;
  private final String channel;

  public EventRelay(
      JdbcTemplate jdbc,
      TransactionTemplate transaction,
      JobGuard guard,
      MeterRegistry meters,
      @Value("${broker.channel:trans_dic1_events}") String channel) {
    this.jdbc = jdbc;
    this.transaction = transaction;
    this.guard = guard;
    this.meters = meters;
    this.channel = channel;
    Gauge.builder("broker.outbox.pending", this, EventRelay::pending)
        .description("Événements écrits mais pas encore publiés")
        .register(meters);
  }

  @Scheduled(fixedDelayString = "${broker.relay-interval-ms:1000}")
  public void scheduledRelay() {
    guard.run("broker.relay", Duration.ofMinutes(1), this::relayPending);
  }

  /** Publie un lot d'événements en attente ; retourne le nombre publié. */
  public int relayPending() {
    Integer published =
        transaction.execute(
            status -> {
              List<Long> ids =
                  jdbc.queryForList(
                      "SELECT id FROM event_outbox WHERE published_at IS NULL ORDER BY id"
                          + " LIMIT ? FOR UPDATE SKIP LOCKED",
                      Long.class,
                      BATCH);
              for (Long id : ids) {
                jdbc.queryForObject("SELECT pg_notify(?, ?)", Object.class, channel, id.toString());
              }
              if (!ids.isEmpty()) {
                jdbc.update(
                    "UPDATE event_outbox SET published_at = now() WHERE id = ANY (?)",
                    (Object) ids.toArray(Long[]::new));
              }
              return ids.size();
            });
    int count = published == null ? 0 : published;
    if (count > 0) {
      meters.counter("broker.events.published").increment(count);
      LOG.debug("{} événement(s) relayé(s)", count);
    }
    return count;
  }

  @Scheduled(cron = "${broker.purge-cron:0 20 4 * * *}", zone = "UTC")
  public void purge() {
    jdbc.update("DELETE FROM event_outbox WHERE published_at < now() - interval '2 days'");
  }

  double pending() {
    try {
      Long count =
          jdbc.queryForObject(
              "SELECT count(*) FROM event_outbox WHERE published_at IS NULL", Long.class);
      return count == null ? 0 : count;
    } catch (RuntimeException e) {
      return Double.NaN;
    }
  }
}

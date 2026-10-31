package sn.ept.transdic1.overview.broker;

import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import sn.ept.transdic1.overview.service.LiveUpdateService;

/**
 * Abonné de chaque instance : écoute le canal PostgreSQL ({@code LISTEN}) sur une connexion dédiée
 * et transmet les événements reçus aux écrans connectés à cette instance. Livraison au plus une
 * fois par événement (identifiant mémorisé) ; après une coupure, l'abonné relit dans la table les
 * événements publiés depuis le dernier reçu : une notification manquée n'est pas un événement
 * perdu.
 */
@Component
@ConditionalOnProperty(name = "broker.enabled", havingValue = "true")
public class EventSubscriber implements SmartLifecycle {
  private static final Logger LOG = LoggerFactory.getLogger(EventSubscriber.class);
  private static final int REMEMBERED = 10_000;

  private final DataSource dataSource;
  private final JdbcTemplate jdbc;
  private final LiveUpdateService live;
  private final MeterRegistry meters;
  private final String channel;
  private final Set<UUID> seen =
      Collections.newSetFromMap(
          new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<UUID, Boolean> eldest) {
              return size() > REMEMBERED;
            }
          });
  private final AtomicLong lastSeenId = new AtomicLong(-1);
  private final AtomicLong delivered = new AtomicLong();
  private volatile boolean running;
  private Thread worker;

  public EventSubscriber(
      DataSource dataSource,
      JdbcTemplate jdbc,
      LiveUpdateService live,
      MeterRegistry meters,
      @Value("${broker.channel:trans_dic1_events}") String channel) {
    this.dataSource = dataSource;
    this.jdbc = jdbc;
    this.live = live;
    this.meters = meters;
    this.channel = channel;
  }

  @Override
  public synchronized void start() {
    if (running) {
      return;
    }
    if (lastSeenId.get() < 0) {
      // Au premier démarrage, on ne rejoue pas l'historique : on part du dernier événement publié.
      Long max = jdbc.queryForObject("SELECT COALESCE(max(id), 0) FROM event_outbox", Long.class);
      lastSeenId.set(max == null ? 0 : max);
    }
    running = true;
    worker = new Thread(this::listen, "broker-subscriber");
    worker.setDaemon(true);
    worker.start();
  }

  @Override
  public synchronized void stop() {
    running = false;
    if (worker != null) {
      worker.interrupt();
      try {
        worker.join(5000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  /**
   * Nombre d'événements transmis aux écrans depuis le démarrage (pour la supervision et les tests).
   */
  public long delivered() {
    return delivered.get();
  }

  private void listen() {
    long backoff = 1000;
    while (running) {
      try (Connection connection = dataSource.getConnection()) {
        try (Statement statement = connection.createStatement()) {
          statement.execute("LISTEN " + channel);
        }
        PGConnection pg = connection.unwrap(PGConnection.class);
        catchUp();
        backoff = 1000;
        while (running) {
          PGNotification[] notifications = pg.getNotifications(1000);
          if (notifications != null) {
            for (PGNotification notification : notifications) {
              deliver(Long.parseLong(notification.getParameter()));
            }
          }
        }
      } catch (Exception e) {
        if (!running) {
          return;
        }
        LOG.warn(
            "Abonné du broker déconnecté ({}), nouvel essai dans {} ms",
            e.getClass().getSimpleName(),
            backoff);
        meters.counter("broker.subscriber.reconnects").increment();
        try {
          Thread.sleep(backoff);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          return;
        }
        backoff = Math.min(backoff * 2, 30_000);
      }
    }
  }

  /** Événements publiés pendant que l'abonné n'écoutait pas. */
  void catchUp() {
    jdbc.queryForList(
            "SELECT id FROM event_outbox WHERE id > ? AND published_at IS NOT NULL ORDER BY id",
            Long.class,
            lastSeenId.get())
        .forEach(this::deliver);
  }

  /** Transmet un événement aux écrans de cette instance, une seule fois. */
  void deliver(long id) {
    var rows =
        jdbc.query(
            "SELECT event_id, type, payload FROM event_outbox WHERE id = ?",
            (rs, i) -> new Object[] {rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)},
            id);
    if (rows.isEmpty()) {
      return;
    }
    UUID eventId = (UUID) rows.get(0)[0];
    synchronized (seen) {
      if (!seen.add(eventId)) {
        meters.counter("broker.events.duplicates").increment();
        return;
      }
    }
    lastSeenId.accumulateAndGet(id, Math::max);
    if (EventOutboxWriter.ALERT_ACTIVITY.equals(rows.get(0)[1])) {
      live.relay((String) rows.get(0)[2]);
    }
    delivered.incrementAndGet();
    meters.counter("broker.events.delivered").increment();
  }
}

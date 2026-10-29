package sn.ept.transdic1.common.scheduling;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Exécute une tâche planifiée sous un verrou stocké en base : avec plusieurs instances du backend,
 * une seule exécute la tâche à un instant donné. Le verrou expire de lui-même après {@code
 * lockAtMost}, ce qui borne la durée d'une tâche et libère le verrou si l'instance meurt. L'état de
 * chaque exécution (succès, échec, ignorée, durée) est conservé en base et exposé en métriques.
 */
@Component
public class JobGuard {
  private static final Logger LOG = LoggerFactory.getLogger(JobGuard.class);

  private final JdbcTemplate jdbc;
  private final MeterRegistry meters;
  private final Clock clock;
  private final String owner = UUID.randomUUID().toString().substring(0, 8);

  public JobGuard(JdbcTemplate jdbc, MeterRegistry meters, Clock clock) {
    this.jdbc = jdbc;
    this.meters = meters;
    this.clock = clock;
  }

  /**
   * Exécute {@code task} si le verrou {@code name} est libre. Retourne son résultat, ou vide si une
   * autre instance tient le verrou. Une exception de la tâche est comptée puis relancée.
   */
  public <T> Optional<T> run(String name, Duration lockAtMost, Supplier<T> task) {
    Instant started = clock.instant();
    if (!acquire(name, started, lockAtMost)) {
      LOG.debug("Tâche {} ignorée : verrou tenu par une autre instance", name);
      record(name, "SKIPPED", started, Duration.ZERO);
      return Optional.empty();
    }
    String status = "SUCCESS";
    try {
      return Optional.ofNullable(task.get());
    } catch (RuntimeException e) {
      status = "FAILURE";
      LOG.warn("Tâche {} en échec : {}", name, e.getClass().getSimpleName());
      throw e;
    } finally {
      Duration duration = Duration.between(started, clock.instant());
      release(name);
      record(name, status, started, duration);
    }
  }

  /** Prend le verrou s'il est libre ou expiré ; une seule requête, donc atomique. */
  private boolean acquire(String name, Instant now, Duration lockAtMost) {
    Timestamp at = Timestamp.from(now);
    Timestamp until = Timestamp.from(now.plus(lockAtMost));
    int rows =
        jdbc.update(
            "INSERT INTO job_lock(name, locked_until, locked_at, locked_by) VALUES (?, ?, ?, ?)"
                + " ON CONFLICT (name) DO UPDATE SET locked_until = EXCLUDED.locked_until,"
                + " locked_at = EXCLUDED.locked_at, locked_by = EXCLUDED.locked_by"
                + " WHERE job_lock.locked_until <= ?",
            name,
            until,
            at,
            owner,
            at);
    return rows == 1;
  }

  private void release(String name) {
    jdbc.update(
        "UPDATE job_lock SET locked_until = ? WHERE name = ? AND locked_by = ?",
        Timestamp.from(clock.instant()),
        name,
        owner);
  }

  private void record(String name, String status, Instant started, Duration duration) {
    meters.counter("scheduled.job.runs", "job", name, "status", status).increment();
    if (!"SKIPPED".equals(status)) {
      Timer.builder("scheduled.job.duration").tag("job", name).register(meters).record(duration);
    }
    jdbc.update(
        "INSERT INTO job_run(name, last_status, last_started_at, last_duration_ms, success_count,"
            + " failure_count, skipped_count) VALUES (?, ?, ?, ?, ?, ?, ?)"
            + " ON CONFLICT (name) DO UPDATE SET"
            + " last_status = CASE WHEN EXCLUDED.last_status = 'SKIPPED' THEN job_run.last_status"
            + " ELSE EXCLUDED.last_status END,"
            + " last_started_at = CASE WHEN EXCLUDED.last_status = 'SKIPPED'"
            + " THEN job_run.last_started_at ELSE EXCLUDED.last_started_at END,"
            + " last_duration_ms = CASE WHEN EXCLUDED.last_status = 'SKIPPED'"
            + " THEN job_run.last_duration_ms ELSE EXCLUDED.last_duration_ms END,"
            + " success_count = job_run.success_count + EXCLUDED.success_count,"
            + " failure_count = job_run.failure_count + EXCLUDED.failure_count,"
            + " skipped_count = job_run.skipped_count + EXCLUDED.skipped_count",
        name,
        status,
        Timestamp.from(started),
        duration.toMillis(),
        "SUCCESS".equals(status) ? 1 : 0,
        "FAILURE".equals(status) ? 1 : 0,
        "SKIPPED".equals(status) ? 1 : 0);
  }
}

package sn.ept.transdic1.common.scheduling;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Expose l'état de chaque tâche planifiée enregistré dans job_run, y compris celles qui tournent
 * hors du backend (sauvegarde PostgreSQL) : date de la dernière exécution et succès ou échec. Une
 * alerte peut ainsi signaler une tâche en échec ou qui ne s'exécute plus. Relu toutes les 30 s.
 */
@Component
public class JobRunMetrics {
  private static final Logger LOG = LoggerFactory.getLogger(JobRunMetrics.class);

  private final JdbcTemplate jdbc;
  private final MultiGauge lastRun;
  private final MultiGauge lastSuccess;

  public JobRunMetrics(JdbcTemplate jdbc, MeterRegistry registry) {
    this.jdbc = jdbc;
    this.lastRun =
        MultiGauge.builder("scheduled.job.last.run")
            .description("Date de la dernière exécution, en secondes depuis l'époque Unix")
            .baseUnit("seconds")
            .register(registry);
    this.lastSuccess =
        MultiGauge.builder("scheduled.job.last.success")
            .description("1 si la dernière exécution a réussi, 0 sinon")
            .register(registry);
  }

  @Scheduled(initialDelay = 0, fixedDelay = 30_000)
  public void refresh() {
    List<JobRun> runs;
    try {
      runs =
          jdbc.query(
              "SELECT name, last_status, EXTRACT(EPOCH FROM last_started_at) AS started FROM job_run",
              (rs, i) ->
                  new JobRun(
                      rs.getString("name"), rs.getString("last_status"), rs.getDouble("started")));
    } catch (DataAccessException e) {
      LOG.warn("État des tâches planifiées illisible : {}", e.getClass().getSimpleName());
      return;
    }
    lastRun.register(
        runs.stream()
            .map(r -> MultiGauge.Row.of(Tags.of("job", r.name()), r.startedEpoch()))
            .toList(),
        true);
    lastSuccess.register(
        runs.stream()
            .map(
                r ->
                    MultiGauge.Row.of(
                        Tags.of("job", r.name()), "SUCCESS".equals(r.status()) ? 1 : 0))
            .toList(),
        true);
  }

  private record JobRun(String name, String status, double startedEpoch) {}
}

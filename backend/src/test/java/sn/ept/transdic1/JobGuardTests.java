package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import sn.ept.transdic1.common.scheduling.JobGuard;

/** Verrou des tâches planifiées : deux instances simulées ne se chevauchent jamais. */
@SpringBootTest
@ActiveProfiles("test")
class JobGuardTests {
  private static final String JOB = "test.job";

  @Autowired private JdbcTemplate jdbc;
  @Autowired private MeterRegistry meters;
  @Autowired private Clock clock;
  @Autowired private JobGuard first;

  @AfterEach
  void clean() {
    jdbc.update("DELETE FROM job_lock WHERE name LIKE 'test.%'");
    jdbc.update("DELETE FROM job_run WHERE name LIKE 'test.%'");
  }

  @Test
  void uneSeuleInstanceExecuteLaTacheEnMemeTemps() throws Exception {
    JobGuard second = new JobGuard(jdbc, meters, clock);
    CountDownLatch inside = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger runs = new AtomicInteger();

    Thread holder =
        new Thread(
            () ->
                first.run(
                    JOB,
                    Duration.ofMinutes(1),
                    () -> {
                      runs.incrementAndGet();
                      inside.countDown();
                      await(release);
                      return 1;
                    }));
    holder.start();
    assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();

    Optional<Integer> other =
        second.run(
            JOB,
            Duration.ofMinutes(1),
            () -> {
              runs.incrementAndGet();
              return 2;
            });

    release.countDown();
    holder.join(5000);
    assertThat(other).isEmpty();
    assertThat(runs).hasValue(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT skipped_count FROM job_run WHERE name = ?", Long.class, JOB))
        .isEqualTo(1);
  }

  @Test
  void leVerrouEstLibereApresLExecution() {
    assertThat(first.run(JOB, Duration.ofMinutes(1), () -> "a")).contains("a");
    assertThat(first.run(JOB, Duration.ofMinutes(1), () -> "b")).contains("b");
    Long ok =
        jdbc.queryForObject("SELECT success_count FROM job_run WHERE name = ?", Long.class, JOB);
    assertThat(ok).isEqualTo(2);
  }

  @Test
  void leVerrouExpireSeulSiLInstanceDisparait() {
    jdbc.update(
        "INSERT INTO job_lock(name, locked_until, locked_at, locked_by) VALUES (?, now() - interval '1 second', now() - interval '1 minute', 'morte')",
        JOB);
    assertThat(first.run(JOB, Duration.ofMinutes(1), () -> "repris")).contains("repris");
  }

  @Test
  void unEchecEstCompteEtRelance() {
    assertThatThrownBy(
            () ->
                first.run(
                    JOB,
                    Duration.ofMinutes(1),
                    () -> {
                      throw new IllegalStateException("boom");
                    }))
        .isInstanceOf(IllegalStateException.class);

    assertThat(
            jdbc.queryForObject(
                "SELECT last_status FROM job_run WHERE name = ?", String.class, JOB))
        .isEqualTo("FAILURE");
    assertThat(
            jdbc.queryForObject(
                "SELECT failure_count FROM job_run WHERE name = ?", Long.class, JOB))
        .isEqualTo(1);
    // Le verrou est rendu : la tâche suivante peut repartir.
    assertThat(first.run(JOB, Duration.ofMinutes(1), () -> "ok")).contains("ok");
    assertThat(
            meters
                .get("scheduled.job.runs")
                .tag("job", JOB)
                .tag("status", "FAILURE")
                .counter()
                .count())
        .isGreaterThanOrEqualTo(1);
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await(10, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}

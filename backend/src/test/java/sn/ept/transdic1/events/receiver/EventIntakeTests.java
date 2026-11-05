package sn.ept.transdic1.events.receiver;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import sn.ept.transdic1.events.domain.EventSeverity;
import sn.ept.transdic1.events.domain.EventSource;
import sn.ept.transdic1.events.service.EventMetrics;

/** File bornée, pool de traitement et limitation de débit par adresse source. */
class EventIntakeTests {

  private static ReceivedEvent event(String address) {
    return new ReceivedEvent(
        EventSource.SYSLOG,
        address,
        EventSeverity.ERROR,
        "titre",
        "détails",
        null,
        null,
        null,
        null,
        Instant.now());
  }

  @Test
  void laLimiteParSourceEstRéinitialiséeChaqueSeconde() {
    AtomicLong now = new AtomicLong(10_000);
    var limiter = new SourceRateLimiter(3, now::get);

    assertThat(limiter.tryAcquire("10.0.0.1")).isTrue();
    assertThat(limiter.tryAcquire("10.0.0.1")).isTrue();
    assertThat(limiter.tryAcquire("10.0.0.1")).isTrue();
    assertThat(limiter.tryAcquire("10.0.0.1")).isFalse();
    // Une autre source a son propre compteur.
    assertThat(limiter.tryAcquire("10.0.0.2")).isTrue();

    now.addAndGet(1_000);
    assertThat(limiter.tryAcquire("10.0.0.1")).isTrue();
  }

  @Test
  void leLimiteurNeGardePasUnNombreIllimitéDeSources() {
    AtomicLong now = new AtomicLong(0);
    var limiter = new SourceRateLimiter(1, now::get);
    for (int i = 0; i < SourceRateLimiter.MAX_SOURCES * 2; i++) {
      limiter.tryAcquire("10." + (i >> 16 & 255) + "." + (i >> 8 & 255) + "." + (i & 255));
    }
    assertThat(limiter.trackedSources()).isLessThanOrEqualTo(SourceRateLimiter.MAX_SOURCES);
  }

  @Test
  void uneSourceTropBavardeEstLimitéeEtComptée() throws Exception {
    var registry = new SimpleMeterRegistry();
    var metrics = new EventMetrics(registry);
    AtomicInteger handled = new AtomicInteger();
    var done = new CountDownLatch(2);
    var intake =
        new EventIntake(
            100,
            1,
            new SourceRateLimiter(2, () -> 0L),
            e -> {
              handled.incrementAndGet();
              done.countDown();
            },
            metrics);
    intake.start();
    try {
      for (int i = 0; i < 5; i++) {
        intake.submit(event("10.0.0.9"));
      }
      assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(registry.counter("events.dropped", "reason", "rate_limited").count())
          .isEqualTo(3.0);
      assertThat(handled.get()).isEqualTo(2);
    } finally {
      intake.close();
    }
  }

  @Test
  void uneFilePleineRejetteSansBloquerEtCompte() throws Exception {
    var registry = new SimpleMeterRegistry();
    var metrics = new EventMetrics(registry);
    var release = new CountDownLatch(1);
    var started = new CountDownLatch(1);
    var intake =
        new EventIntake(
            2,
            1,
            new SourceRateLimiter(1_000, System::currentTimeMillis),
            e -> {
              started.countDown();
              try {
                release.await(5, TimeUnit.SECONDS);
              } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
              }
            },
            metrics);
    intake.start();
    try {
      assertThat(intake.submit(event("10.0.0.1"))).isTrue();
      assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
      // Le travailleur est occupé : deux places dans la file, puis rejet immédiat.
      long before = System.nanoTime();
      assertThat(intake.submit(event("10.0.0.1"))).isTrue();
      assertThat(intake.submit(event("10.0.0.1"))).isTrue();
      assertThat(intake.submit(event("10.0.0.1"))).isFalse();
      assertThat(intake.submit(event("10.0.0.1"))).isFalse();
      assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before)).isLessThan(1_000);
      assertThat(registry.counter("events.dropped", "reason", "queue_full").count()).isEqualTo(2.0);
    } finally {
      release.countDown();
      intake.close();
    }
  }

  @Test
  void laFermetureArrêteLesTravailleurs() throws Exception {
    var intake =
        new EventIntake(
            10,
            2,
            new SourceRateLimiter(10, () -> 0L),
            e -> {},
            new EventMetrics(new SimpleMeterRegistry()));
    intake.start();
    intake.start(); // idempotent
    intake.close();

    assertThat(intake.isRunning()).isFalse();
    assertThat(intake.submit(event("10.0.0.1"))).isFalse();
  }
}

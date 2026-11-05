package sn.ept.transdic1.events.receiver;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
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
            a -> true,
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
            a -> true,
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
  void uneSourceBavardeRéelleNEstPasRéinitialiséeQuandLaTableEstPleine() {
    AtomicLong now = new AtomicLong(0);
    var limiter = new SourceRateLimiter(1, now::get);
    for (int i = 0; i < SourceRateLimiter.MAX_SOURCES - 1; i++) {
      limiter.tryAcquire("10." + (i >> 16 & 255) + "." + (i >> 8 & 255) + "." + (i & 255));
    }
    now.set(5_000);
    assertThat(limiter.tryAcquire("192.0.2.1")).isTrue();
    assertThat(limiter.tryAcquire("192.0.2.1")).isFalse();

    // Une nouvelle source arrive table pleine : on évince la plus ancienne, pas la source bavarde.
    assertThat(limiter.tryAcquire("192.0.2.2")).isTrue();
    assertThat(limiter.tryAcquire("192.0.2.1")).isFalse();
    assertThat(limiter.trackedSources()).isLessThanOrEqualTo(SourceRateLimiter.MAX_SOURCES);
  }

  @Test
  void desSourcesUsurpéesNAtteignentNiLeLimiteurNiLaFile() throws Exception {
    var registry = new SimpleMeterRegistry();
    var limiter = new SourceRateLimiter(1_000, () -> 0L);
    AtomicInteger handled = new AtomicInteger();
    var intake =
        new EventIntake(
            100,
            2,
            "10.0.0.1"::equals,
            limiter,
            e -> handled.incrementAndGet(),
            new EventMetrics(registry));
    intake.start();
    try {
      var random = ThreadLocalRandom.current();
      for (int i = 0; i < 5_000; i++) {
        String spoofed =
            "172." + random.nextInt(16, 32) + "." + random.nextInt(256) + "." + random.nextInt(256);
        assertThat(intake.submit(event(spoofed))).isFalse();
      }
      assertThat(intake.queued()).isZero();
      assertThat(limiter.trackedSources()).isZero();
      assertThat(registry.counter("events.dropped", "reason", "unknown_source").count())
          .isEqualTo(5_000.0);
      assertThat(intake.submit(event("10.0.0.1"))).isTrue();
      Thread.sleep(200);
      assertThat(handled.get()).isEqualTo(1);
    } finally {
      intake.close();
    }
  }

  @Test
  void lesÉvénementsDUneMêmeSourceSontTraitésDansLOrdreMêmeAvecPlusieursTravailleurs()
      throws Exception {
    int count = 300;
    var seen = new ConcurrentHashMap<String, List<Integer>>();
    var done = new CountDownLatch(count * 2);
    var intake =
        new EventIntake(
            4_000,
            4,
            a -> true,
            new SourceRateLimiter(10_000, () -> 0L),
            e -> {
              if (ThreadLocalRandom.current().nextInt(4) == 0) {
                try {
                  Thread.sleep(1);
                } catch (InterruptedException ex) {
                  Thread.currentThread().interrupt();
                }
              }
              seen.computeIfAbsent(
                      e.sourceAddress(), k -> Collections.synchronizedList(new ArrayList<>()))
                  .add(Integer.parseInt(e.title()));
              done.countDown();
            },
            new EventMetrics(new SimpleMeterRegistry()));
    intake.start();
    try {
      for (int i = 0; i < count; i++) {
        for (String source : new String[] {"10.0.0.1", "10.0.0.2"}) {
          assertThat(
                  intake.submit(
                      new ReceivedEvent(
                          EventSource.SNMP_TRAP,
                          source,
                          EventSeverity.ERROR,
                          String.valueOf(i),
                          null,
                          null,
                          null,
                          null,
                          null,
                          Instant.now())))
              .isTrue();
        }
      }
      assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
      var expected = IntStream.range(0, count).boxed().toList();
      assertThat(seen.get("10.0.0.1")).containsExactlyElementsOf(expected);
      assertThat(seen.get("10.0.0.2")).containsExactlyElementsOf(expected);
    } finally {
      intake.close();
    }
  }

  @Test
  void laFermetureArrêteLesTravailleurs() throws Exception {
    var intake =
        new EventIntake(
            10,
            2,
            a -> true,
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

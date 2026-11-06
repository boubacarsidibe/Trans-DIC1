package sn.ept.transdic1.statuspage.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Cache des pages de statut : un calcul par période, même sous une rafale concurrente. */
class TtlCacheTests {
  /** Horloge réglable à la main. */
  private static final class MutableClock extends Clock {
    private Instant now = Instant.parse("2026-11-06T12:00:00Z");

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  private final MutableClock clock = new MutableClock();
  private final TtlCache<String, Integer> cache = new TtlCache<>(clock, Duration.ofSeconds(30), 8);
  private final AtomicInteger loads = new AtomicInteger();

  private Integer load() {
    return loads.incrementAndGet();
  }

  @Test
  void uneSeuleLectureParPeriode() {
    assertThat(cache.get("p", this::load)).isEqualTo(1);
    clock.now = clock.now.plusSeconds(29);
    assertThat(cache.get("p", this::load)).isEqualTo(1);
    clock.now = clock.now.plusSeconds(1);
    assertThat(cache.get("p", this::load)).isEqualTo(2);
    assertThat(loads).hasValue(2);
  }

  @Test
  void lInvalidationForceLeRecalcul() {
    cache.get("p", this::load);
    cache.invalidate("p");
    assertThat(cache.get("p", this::load)).isEqualTo(2);
  }

  @Test
  void uneRafaleConcurrenteNeCalculeQuUneFois() throws Exception {
    var pool = Executors.newFixedThreadPool(16);
    var start = new CountDownLatch(1);
    var results = new ArrayList<Future<Integer>>();
    for (int i = 0; i < 200; i++) {
      results.add(
          pool.submit(
              () -> {
                start.await();
                return cache.get(
                    "p",
                    () -> {
                      try {
                        Thread.sleep(50);
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                      }
                      return load();
                    });
              }));
    }
    start.countDown();
    for (var result : results) {
      assertThat(result.get(10, TimeUnit.SECONDS)).isEqualTo(1);
    }
    pool.shutdown();
    assertThat(loads).hasValue(1);
  }

  @Test
  void leNombreDEntreesEstBorne() {
    for (int i = 0; i < 20; i++) {
      cache.get("k" + i, this::load);
    }
    // Au plus 8 entrées gardées : la première a été oubliée et se recalcule.
    int before = loads.get();
    cache.get("k0", this::load);
    assertThat(loads.get()).isEqualTo(before + 1);
  }
}

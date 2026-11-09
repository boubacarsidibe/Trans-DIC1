package sn.ept.transdic1.events.receiver;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import sn.ept.transdic1.events.service.EventMetrics;

/**
 * Limitation de débit et file pleine vérifiées sur un vrai socket UDP : un récepteur syslog réel,
 * branché sur une file dont le travailleur est contrôlé par le test.
 */
class ReceiverSocketTests {

  private static boolean await(BooleanSupplier condition) throws InterruptedException {
    long end = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (System.nanoTime() < end) {
      if (condition.getAsBoolean()) {
        return true;
      }
      Thread.sleep(20);
    }
    return condition.getAsBoolean();
  }

  private static void send(int port, int count) throws Exception {
    byte[] data = "<11>Nov  5 10:00:00 r1 kernel: down".getBytes(StandardCharsets.UTF_8);
    try (var socket = new DatagramSocket()) {
      for (int i = 0; i < count; i++) {
        socket.send(new DatagramPacket(data, data.length, InetAddress.getLoopbackAddress(), port));
      }
    }
  }

  @Test
  void uneSourceTropBavardeEstLimitéeSurUnVraiSocket() throws Exception {
    var registry = new SimpleMeterRegistry();
    var metrics = new EventMetrics(registry);
    var intake =
        new EventIntake(
            1_000, 1, "127.0.0.1"::equals, new SourceRateLimiter(5, () -> 0L), e -> {}, metrics);
    var receiver = new SyslogReceiver(intake, metrics, Clock.systemUTC(), "127.0.0.1", 0);
    receiver.start();
    try {
      send(receiver.port(), 20);
      assertThat(
              await(
                  () ->
                      registry.counter("events.received", "source", "SYSLOG").count() >= 20
                          && registry.counter("events.dropped", "reason", "rate_limited").count()
                              == 15))
          .isTrue();
    } finally {
      receiver.stop();
      intake.close();
    }
  }

  @Test
  void uneFilePleineRejetteSurUnVraiSocketSansBloquerLeRécepteur() throws Exception {
    var registry = new SimpleMeterRegistry();
    var metrics = new EventMetrics(registry);
    var release = new CountDownLatch(1);
    var intake =
        new EventIntake(
            2,
            1,
            "127.0.0.1"::equals,
            new SourceRateLimiter(1_000, () -> 0L),
            e -> {
              try {
                release.await(10, TimeUnit.SECONDS);
              } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
              }
            },
            metrics);
    var receiver = new SyslogReceiver(intake, metrics, Clock.systemUTC(), "127.0.0.1", 0);
    receiver.start();
    try {
      // Un en cours de traitement, deux en file, le reste rejeté.
      send(receiver.port(), 10);
      assertThat(
              await(() -> registry.counter("events.dropped", "reason", "queue_full").count() >= 7))
          .isTrue();
    } finally {
      release.countDown();
      receiver.stop();
      intake.close();
    }
  }
}

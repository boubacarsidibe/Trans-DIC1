package sn.ept.transdic1.collection.probe;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import sn.ept.transdic1.inventory.domain.ServiceType;

class IcmpProbeTests {
  private static ProbeRequest request(InetAddress address, String host, Duration timeout) {
    return new ProbeRequest(address, host, null, null, timeout, null, null);
  }

  private static InetAddress ip(String text) throws Exception {
    return InetAddress.getByAddress(
        InetAddress.getByName(text).getAddress()); // pas de nom d'hôte mémorisé
  }

  private static final class RecordingRunner implements IcmpProbe.CommandRunner {
    final List<List<String>> commands = new ArrayList<>();
    final List<Duration> timeouts = new ArrayList<>();
    private final IcmpProbe.CommandOutput output;
    private final IOException failure;

    RecordingRunner(IcmpProbe.CommandOutput output, IOException failure) {
      this.output = output;
      this.failure = failure;
    }

    @Override
    public IcmpProbe.CommandOutput run(List<String> command, Duration timeout) throws IOException {
      commands.add(command);
      timeouts.add(timeout);
      if (failure != null) {
        throw failure;
      }
      return output;
    }
  }

  private static RecordingRunner ok(String stdout) {
    return new RecordingRunner(new IcmpProbe.CommandOutput(0, stdout), null);
  }

  @Test
  void typeIsIcmp() {
    assertThat(new IcmpProbe(ok("")).type()).isEqualTo(ServiceType.ICMP);
  }

  @Test
  void usesArgumentArrayWithoutShell() throws Exception {
    RecordingRunner linux = ok("time=1 ms");
    new IcmpProbe(linux, a -> false, false)
        .run(request(ip("192.0.2.10"), "evil; rm -rf /", Duration.ofMillis(1500)));
    assertThat(linux.commands.get(0)).containsExactly("ping", "-c", "1", "-W", "2", "192.0.2.10");

    RecordingRunner windows = ok("temps=1 ms TTL=64");
    new IcmpProbe(windows, a -> false, true)
        .run(request(ip("192.0.2.10"), "h", Duration.ofMillis(1500)));
    assertThat(windows.commands.get(0))
        .containsExactly("ping", "-n", "1", "-w", "1500", "192.0.2.10");
  }

  @Test
  void linuxTimeoutIsAtLeastOneSecond() throws Exception {
    RecordingRunner runner = ok("time=1 ms");
    new IcmpProbe(runner, a -> false, false)
        .run(request(ip("192.0.2.10"), null, Duration.ofMillis(100)));
    assertThat(runner.commands.get(0)).containsExactly("ping", "-c", "1", "-W", "1", "192.0.2.10");
  }

  @Test
  void parsesLatencyFromOutput() throws Exception {
    assertThat(probeFor("64 bytes from 192.0.2.10: icmp_seq=1 ttl=64 time=12.3 ms").latencyMs())
        .isEqualTo(12);
    assertThat(probeFor("Réponse de 192.0.2.10 : octets=32 temps=12 ms TTL=64").latencyMs())
        .isEqualTo(12);
    assertThat(probeFor("Reply from 192.0.2.10: bytes=32 time<1ms TTL=64").latencyMs())
        .isEqualTo(1);
    assertThat(probeFor("Réponse de 192.0.2.10 : octets=32 temps<1ms TTL=64").latencyMs())
        .isEqualTo(1);
    assertThat(probeFor("Reply from 192.0.2.10: time=7ms").up()).isTrue();
  }

  private static ProbeResult probeFor(String stdout) {
    try {
      return new IcmpProbe(ok(stdout), a -> false, false)
          .run(request(ip("192.0.2.10"), null, Duration.ofSeconds(2)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void usesMeasuredDurationWhenNoLatencyInOutput() {
    ProbeResult result = probeFor("some unparsable output");
    assertThat(result.up()).isTrue();
    assertThat(result.latencyMs()).isNotNull().isGreaterThanOrEqualTo(0);
  }

  @Test
  void downOnNonZeroExit() throws Exception {
    RecordingRunner runner =
        new RecordingRunner(new IcmpProbe.CommandOutput(1, "secret stderr-ish text"), null);
    ProbeResult result =
        new IcmpProbe(runner, a -> true, false)
            .run(request(ip("192.0.2.10"), null, Duration.ofSeconds(2)));
    assertThat(result.up()).isFalse();
    assertThat(result.error()).isEqualTo("Hôte injoignable").doesNotContain("secret");
  }

  @Test
  void downOnTimeout() throws Exception {
    AtomicBoolean fallbackCalled = new AtomicBoolean();
    RecordingRunner runner = new RecordingRunner(null, new InterruptedIOException("boom"));
    ProbeResult result =
        new IcmpProbe(
                runner,
                a -> {
                  fallbackCalled.set(true);
                  return true;
                },
                false)
            .run(request(ip("192.0.2.10"), null, Duration.ofSeconds(2)));
    assertThat(result.up()).isFalse();
    assertThat(result.error()).isEqualTo("Délai dépassé");
    assertThat(fallbackCalled).isFalse();
  }

  @Test
  void fallsBackToIsReachableWhenCommandMissing() throws Exception {
    RecordingRunner runner = new RecordingRunner(null, new IOException("Cannot run program"));
    List<InetAddress> asked = new ArrayList<>();
    IcmpProbe probe =
        new IcmpProbe(
            runner,
            a -> {
              asked.add(a);
              return true;
            },
            false);
    InetAddress address = ip("192.0.2.10");
    ProbeResult up = probe.run(request(address, null, Duration.ofSeconds(2)));
    assertThat(up.up()).isTrue();
    assertThat(up.latencyMs()).isNotNull();
    assertThat(asked).containsExactly(address);

    ProbeResult down =
        new IcmpProbe(runner, a -> false, false).run(request(address, null, Duration.ofSeconds(2)));
    assertThat(down.up()).isFalse();
    assertThat(down.error()).isEqualTo("Hôte injoignable").doesNotContain("Cannot");
  }

  @Test
  void neverPassesHostName() throws Exception {
    RecordingRunner runner = ok("time=1 ms");
    new IcmpProbe(runner, a -> false, false)
        .run(request(ip("192.0.2.10"), "victim.example.com", Duration.ofSeconds(2)));
    assertThat(String.join(" ", runner.commands.get(0))).doesNotContain("victim");
    assertThat(runner.commands.get(0).get(runner.commands.get(0).size() - 1))
        .isEqualTo("192.0.2.10");
  }

  @Test
  void noArgumentStartsWithDashExceptFixedFlags() throws Exception {
    RecordingRunner runner = ok("time=1 ms");
    new IcmpProbe(runner, a -> false, false)
        .run(request(ip("192.0.2.10"), "-evil", Duration.ofSeconds(2)));
    List<String> command = runner.commands.get(0);
    assertThat(command.stream().filter(a -> a.startsWith("-"))).containsExactly("-c", "-W");
  }

  @Test
  void scopedIpv6NeverPutsPercentInCommand() throws Exception {
    byte[] bytes = InetAddress.getByName("fe80::1").getAddress();
    Inet6Address scoped = Inet6Address.getByAddress(null, bytes, 3);
    assertThat(scoped.getHostAddress()).contains("%");
    RecordingRunner runner = ok("time=1 ms");
    new IcmpProbe(runner, a -> false, false).run(request(scoped, "h", Duration.ofSeconds(2)));
    List<String> command = runner.commands.get(0);
    assertThat(command).noneMatch(a -> a.contains("%"));
    assertThat(command.get(command.size() - 1)).startsWith("fe80:");
  }
}

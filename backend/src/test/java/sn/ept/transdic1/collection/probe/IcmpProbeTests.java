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
    new IcmpProbe(linux, (a, t) -> false, false)
        .run(request(ip("192.0.2.10"), "evil; rm -rf /", Duration.ofMillis(1500)));
    assertThat(linux.commands.get(0)).containsExactly("ping", "-c", "1", "-W", "2", "192.0.2.10");

    RecordingRunner windows = ok("temps=1 ms TTL=64");
    new IcmpProbe(windows, (a, t) -> false, true)
        .run(request(ip("192.0.2.10"), "h", Duration.ofMillis(1500)));
    assertThat(windows.commands.get(0).get(0).toLowerCase()).matches(".*ping([.]exe)?");
    assertThat(windows.commands.get(0).subList(1, 6))
        .containsExactly("-n", "1", "-w", "1500", "192.0.2.10");
  }

  @Test
  void linuxTimeoutIsAtLeastOneSecond() throws Exception {
    RecordingRunner runner = ok("time=1 ms");
    new IcmpProbe(runner, (a, t) -> false, false)
        .run(request(ip("192.0.2.10"), null, Duration.ofMillis(100)));
    assertThat(runner.commands.get(0).subList(1, 6))
        .containsExactly("-c", "1", "-W", "1", "192.0.2.10");
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
    assertThat(probeFor("64 bytes from 192.0.2.10: time=0.045 ms").latencyMs()).isEqualTo(1);
  }

  private static ProbeResult probeFor(String stdout) {
    try {
      return new IcmpProbe(ok(stdout), (a, t) -> false, false)
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
        new IcmpProbe(runner, (a, t) -> true, false)
            .run(request(ip("192.0.2.10"), null, Duration.ofSeconds(2)));
    assertThat(result.up()).isFalse();
    assertThat(result.error()).isEqualTo("Hôte injoignable").doesNotContain("secret");
  }

  @Test
  void exitOneMeansNoReplyAndSkipsTheFallback() throws Exception {
    AtomicBoolean fallbackCalled = new AtomicBoolean();
    RecordingRunner runner = new RecordingRunner(new IcmpProbe.CommandOutput(1, ""), null);
    ProbeResult result =
        new IcmpProbe(
                runner,
                (a, t) -> {
                  fallbackCalled.set(true);
                  return true;
                },
                false)
            .run(request(ip("192.0.2.10"), null, Duration.ofSeconds(2)));
    assertThat(result.up()).isFalse();
    assertThat(result.indeterminate()).isFalse();
    assertThat(fallbackCalled).isFalse();
  }

  @Test
  void errorExitOnLinuxUsesTheFallback() throws Exception {
    // iputils : code 2 pour une erreur (« Operation not permitted »), pas pour une absence de
    // réponse.
    List<InetAddress> asked = new ArrayList<>();
    RecordingRunner runner =
        new RecordingRunner(
            new IcmpProbe.CommandOutput(2, "ping: socket: Operation not permitted"), null);
    InetAddress address = ip("192.0.2.10");
    ProbeResult up =
        new IcmpProbe(
                runner,
                (a, t) -> {
                  asked.add(a);
                  return true;
                },
                false)
            .run(request(address, null, Duration.ofSeconds(2)));
    assertThat(up.up()).isTrue();
    assertThat(asked).containsExactly(address);
  }

  @Test
  void errorExitWithUnusableFallbackIsIndeterminate() throws Exception {
    RecordingRunner runner = new RecordingRunner(new IcmpProbe.CommandOutput(2, ""), null);
    ProbeResult result =
        new IcmpProbe(runner, (a, t) -> false, false)
            .run(request(ip("192.0.2.10"), null, Duration.ofSeconds(2)));
    assertThat(result.up()).isFalse();
    assertThat(result.indeterminate()).isTrue();
    assertThat(result.error()).isEqualTo("Ping non autorisé sur le serveur");

    ProbeResult failing =
        new IcmpProbe(
                runner,
                (a, t) -> {
                  throw new IllegalStateException("x");
                },
                false)
            .run(request(ip("192.0.2.10"), null, Duration.ofSeconds(2)));
    assertThat(failing.indeterminate()).isTrue();
  }

  @Test
  void errorExitOnWindowsStaysDownWithoutFallback() throws Exception {
    AtomicBoolean fallbackCalled = new AtomicBoolean();
    RecordingRunner runner = new RecordingRunner(new IcmpProbe.CommandOutput(2, ""), null);
    ProbeResult result =
        new IcmpProbe(
                runner,
                (a, t) -> {
                  fallbackCalled.set(true);
                  return true;
                },
                true)
            .run(request(ip("192.0.2.10"), null, Duration.ofSeconds(2)));
    assertThat(result.up()).isFalse();
    assertThat(result.indeterminate()).isFalse();
    assertThat(fallbackCalled).isFalse();
  }

  @Test
  void downOnTimeout() throws Exception {
    AtomicBoolean fallbackCalled = new AtomicBoolean();
    RecordingRunner runner = new RecordingRunner(null, new InterruptedIOException("boom"));
    ProbeResult result =
        new IcmpProbe(
                runner,
                (a, t) -> {
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
            (a, t) -> {
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
        new IcmpProbe(runner, (a, t) -> false, false)
            .run(request(address, null, Duration.ofSeconds(2)));
    assertThat(down.up()).isFalse();
    assertThat(down.error()).isEqualTo("Hôte injoignable").doesNotContain("Cannot");
  }

  @Test
  void neverPassesHostName() throws Exception {
    RecordingRunner runner = ok("time=1 ms");
    new IcmpProbe(runner, (a, t) -> false, false)
        .run(request(ip("192.0.2.10"), "victim.example.com", Duration.ofSeconds(2)));
    assertThat(String.join(" ", runner.commands.get(0))).doesNotContain("victim");
    assertThat(runner.commands.get(0).get(runner.commands.get(0).size() - 1))
        .isEqualTo("192.0.2.10");
  }

  @Test
  void noArgumentStartsWithDashExceptFixedFlags() throws Exception {
    RecordingRunner runner = ok("time=1 ms");
    new IcmpProbe(runner, (a, t) -> false, false)
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
    new IcmpProbe(runner, (a, t) -> false, false).run(request(scoped, "h", Duration.ofSeconds(2)));
    List<String> command = runner.commands.get(0);
    assertThat(command).noneMatch(a -> a.contains("%"));
    assertThat(command.get(command.size() - 1)).startsWith("fe80:");
  }

  @Test
  void fallbackReceivesRequestTimeout() throws Exception {
    List<Duration> seen = new ArrayList<>();
    RecordingRunner runner = new RecordingRunner(null, new IOException("missing"));
    new IcmpProbe(
            runner,
            (a, t) -> {
              seen.add(t);
              return true;
            },
            false)
        .run(request(ip("192.0.2.10"), null, Duration.ofMillis(1234)));
    assertThat(seen).containsExactly(Duration.ofMillis(1234));
  }

  @Test
  void windowsExitZeroWithoutLatencyIsDown() throws Exception {
    ProbeResult result =
        new IcmpProbe(
                ok("Reply from 10.0.0.1: Destination host unreachable."), (a, t) -> true, true)
            .run(request(ip("192.0.2.10"), null, Duration.ofSeconds(2)));
    assertThat(result.up()).isFalse();
  }

  @Test
  void windowsExitZeroWithLatencyIsUp() throws Exception {
    ProbeResult result =
        new IcmpProbe(
                ok("Réponse de 192.0.2.10 : octets=32 temps=5 ms TTL=64"), (a, t) -> false, true)
            .run(request(ip("192.0.2.10"), null, Duration.ofSeconds(2)));
    assertThat(result.up()).isTrue();
    assertThat(result.latencyMs()).isEqualTo(5);
  }

  private static boolean osIsWindows() {
    return System.getProperty("os.name", "").toLowerCase().contains("win");
  }

  @Test
  void realRunnerPingsLoopback() throws Exception {
    boolean windows = osIsWindows();
    String exe = IcmpProbe.pingExecutable(windows);
    org.junit.jupiter.api.Assumptions.assumeTrue(
        !exe.equals("ping") || pingOnPath(), "binaire ping absent");
    List<String> cmd =
        windows
            ? List.of(exe, "-n", "1", "-w", "2000", "127.0.0.1")
            : List.of(exe, "-c", "1", "-W", "2", "127.0.0.1");
    IcmpProbe.CommandOutput out =
        new IcmpProbe.ProcessCommandRunner().run(cmd, Duration.ofSeconds(5));
    org.junit.jupiter.api.Assumptions.assumeTrue(
        out.exitCode() == 0 || !out.stdout().isEmpty(), "ping non autorisé ici");
    assertThat(out.exitCode()).isZero();
    assertThat(out.stdout().length()).isLessThanOrEqualTo(4096);
  }

  @Test
  void realRunnerKillsProcessOnTimeout() throws Exception {
    List<String> cmd =
        osIsWindows()
            ? List.of(IcmpProbe.pingExecutable(true), "-n", "30", "127.0.0.1")
            : List.of("sleep", "30");
    long start = System.nanoTime();
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> new IcmpProbe.ProcessCommandRunner().run(cmd, Duration.ofMillis(300)))
        .isInstanceOf(InterruptedIOException.class);
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(4));
    // Plus de ping « -n 30 » / sleep 30 orphelin lancé par ce test.
    Thread.sleep(500);
    long leftovers =
        ProcessHandle.allProcesses()
            .filter(
                h ->
                    h.info()
                        .commandLine()
                        .map(c -> c.contains("-n 30 127.0.0.1") || c.equals("sleep 30"))
                        .orElse(false))
            .count();
    assertThat(leftovers).isZero();
  }

  private static boolean pingOnPath() {
    try {
      Process p = new ProcessBuilder("ping", "-h").redirectErrorStream(true).start();
      p.destroyForcibly();
      return true;
    } catch (IOException e) {
      return false;
    }
  }
}

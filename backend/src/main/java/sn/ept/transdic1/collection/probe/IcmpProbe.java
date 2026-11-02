package sn.ept.transdic1.collection.probe;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.InetAddress;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.BiPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import sn.ept.transdic1.inventory.domain.ServiceType;

/**
 * Sonde ICMP : lance la commande {@code ping} du système (jamais via un shell, tableau d'arguments)
 * avec la seule adresse IP validée. Si la commande est absente, repli sur {@code isReachable}.
 */
@Component
public class IcmpProbe implements Probe {
  private static final int MAX_OUTPUT_BYTES = 4096;
  private static final Duration GRACE = Duration.ofSeconds(2);
  private static final Pattern LATENCY =
      Pattern.compile(
          "(?:time|temps|zeit|tiempo)\\s*([=<])\\s*(\\d+(?:[.,]\\d+)?)\\s*ms",
          Pattern.CASE_INSENSITIVE);

  /** Exécute une commande déjà découpée en arguments, sans shell. */
  public interface CommandRunner {
    /**
     * Lance la commande et renvoie sa sortie.
     *
     * @throws InterruptedIOException si le délai est dépassé
     * @throws IOException si la commande ne peut pas être lancée
     */
    CommandOutput run(List<String> command, Duration timeout) throws IOException;
  }

  public record CommandOutput(int exitCode, String stdout) {}

  private final CommandRunner runner;
  private final BiPredicate<InetAddress, Duration> fallback;
  private final boolean windows;

  @Autowired
  public IcmpProbe() {
    this(new ProcessCommandRunner());
  }

  public IcmpProbe(CommandRunner runner) {
    this(runner, IcmpProbe::reachable, isWindows());
  }

  public IcmpProbe(
      CommandRunner runner, BiPredicate<InetAddress, Duration> fallback, boolean windows) {
    this.runner = runner;
    this.fallback = fallback;
    this.windows = windows;
  }

  private static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
  }

  private static boolean reachable(InetAddress address, Duration timeout) {
    try {
      return address.isReachable((int) timeout.toMillis());
    } catch (IOException e) {
      return false;
    }
  }

  @Override
  public ServiceType type() {
    return ServiceType.ICMP;
  }

  @Override
  public ProbeResult run(ProbeRequest request) {
    List<String> command = buildCommand(request.address(), request.timeout().toMillis());
    long start = System.nanoTime();
    try {
      CommandOutput output = runner.run(command, request.timeout());
      if (output.exitCode() != 0) {
        return ProbeResult.down("Hôte injoignable");
      }
      Integer latency = parseLatency(output.stdout());
      if (latency == null) {
        // Windows renvoie parfois 0 pour « hôte inaccessible » : sans temps, pas de réponse.
        return windows ? ProbeResult.down("Hôte injoignable") : ProbeResult.up(elapsedMs(start));
      }
      return ProbeResult.up(latency);
    } catch (InterruptedIOException e) {
      return ProbeResult.down("Délai dépassé");
    } catch (IOException e) {
      return fallbackProbe(request, start);
    } catch (RuntimeException e) {
      return ProbeResult.down("Ping impossible");
    }
  }

  private ProbeResult fallbackProbe(ProbeRequest request, long start) {
    try {
      return fallback.test(request.address(), request.timeout())
          ? ProbeResult.up(elapsedMs(start))
          : ProbeResult.down("Hôte injoignable");
    } catch (RuntimeException e) {
      return ProbeResult.down("Ping impossible");
    }
  }

  private List<String> buildCommand(InetAddress address, long timeoutMs) {
    String ip = address.getHostAddress();
    int zone = ip.indexOf('%');
    if (zone >= 0) {
      ip = ip.substring(0, zone);
    }
    if (windows) {
      return List.of(pingExecutable(true), "-n", "1", "-w", Long.toString(timeoutMs), ip);
    }
    long seconds = Math.max(1L, (timeoutMs + 999L) / 1000L);
    return List.of(pingExecutable(false), "-c", "1", "-W", Long.toString(seconds), ip);
  }

  /** Chemin absolu de ping s'il existe (évite le PATH), sinon le nom simple. */
  static String pingExecutable(boolean windows) {
    if (windows) {
      String root = System.getenv("SystemRoot");
      if (root != null) {
        File exe = new File(new File(root, "System32"), "PING.EXE");
        if (exe.isFile()) {
          return exe.getPath();
        }
      }
      return "ping";
    }
    for (String path : List.of("/bin/ping", "/usr/bin/ping")) {
      if (new File(path).isFile()) {
        return path;
      }
    }
    return "ping";
  }

  private static int elapsedMs(long start) {
    return (int) ((System.nanoTime() - start) / 1_000_000L);
  }

  static Integer parseLatency(String stdout) {
    if (stdout == null) {
      return null;
    }
    Matcher m = LATENCY.matcher(stdout);
    if (!m.find()) {
      return null;
    }
    if ("<".equals(m.group(1))) {
      return 1;
    }
    try {
      return Math.max(1, (int) Math.round(Double.parseDouble(m.group(2).replace(',', '.'))));
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /** Exécution réelle via {@link ProcessBuilder}, sortie bornée à 4 Ko, stderr ignoré. */
  static final class ProcessCommandRunner implements CommandRunner {
    @Override
    public CommandOutput run(List<String> command, Duration timeout) throws IOException {
      ProcessBuilder builder = new ProcessBuilder(command);
      builder.redirectError(ProcessBuilder.Redirect.DISCARD);
      builder.redirectInput(
          ProcessBuilder.Redirect.from(new File(isWindows() ? "NUL" : "/dev/null")));
      Process process = builder.start();
      ByteArrayOutputStream captured = new ByteArrayOutputStream();
      Thread reader = new Thread(() -> drain(process.getInputStream(), captured), "icmp-probe-out");
      reader.setDaemon(true);
      reader.start();
      try {
        long waitMs = timeout.plus(GRACE).toMillis();
        if (!process.waitFor(waitMs, TimeUnit.MILLISECONDS)) {
          process.destroyForcibly();
          throw new InterruptedIOException("timeout");
        }
        reader.join(500);
        byte[] bytes;
        synchronized (captured) {
          bytes = captured.toByteArray();
        }
        return new CommandOutput(process.exitValue(), new String(bytes, outputCharset()));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new InterruptedIOException("interrupted");
      } finally {
        process.destroyForcibly();
        closeQuietly(process.getInputStream());
        closeQuietly(process.getErrorStream());
        closeQuietly(process.getOutputStream());
      }
    }

    private static Charset outputCharset() {
      // La console Windows française n'est pas en UTF-8 ; seuls chiffres et « ms » comptent.
      return StandardCharsets.ISO_8859_1;
    }

    private static void drain(InputStream in, ByteArrayOutputStream captured) {
      byte[] buffer = new byte[1024];
      int total = 0;
      try {
        int n;
        while ((n = in.read(buffer)) >= 0) {
          int keep = Math.min(n, MAX_OUTPUT_BYTES - total);
          if (keep > 0) {
            synchronized (captured) {
              captured.write(buffer, 0, keep);
            }
            total += keep;
          }
        }
      } catch (IOException e) {
        // flux fermé : on garde ce qui a été lu
      }
    }

    private static void closeQuietly(Closeable c) {
      try {
        c.close();
      } catch (IOException e) {
        // ignoré
      }
    }
  }
}

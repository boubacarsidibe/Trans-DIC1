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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.BiPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import sn.ept.transdic1.inventory.domain.ServiceType;

/**
 * Sonde ICMP : lance la commande {@code ping} du système (jamais via un shell, tableau d'arguments)
 * avec la seule adresse IP validée. Si la commande est absente, repli sur {@code isReachable}
 * (négatif = injoignable). Si elle se lance mais sort en erreur (code 2 ou plus, hors Windows),
 * repli aussi sur {@code isReachable} ; un repli négatif donne alors un résultat indéterminé.
 *
 * <p>Avec plusieurs paquets ({@code collection.probe.icmp.packets}, 4 par défaut), la sonde mesure
 * aussi la perte de paquets et la gigue. Les lignes de synthèse de {@code ping} changent selon la
 * langue du système : seules les lignes de réponse, qui portent un temps, sont comptées.
 */
@Component
public class IcmpProbe implements Probe {
  private static final int MAX_OUTPUT_BYTES = 4096;
  private static final Duration GRACE = Duration.ofSeconds(2);
  static final String PING_NOT_ALLOWED = "Ping non autorisé sur le serveur";
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

  /** Paquets au plus sous Windows, qui attend une seconde entre deux envois. */
  static final int MAX_WINDOWS_PACKETS = 3;

  private final CommandRunner runner;
  private final BiPredicate<InetAddress, Duration> fallback;
  private final boolean windows;
  private final int packets;

  /**
   * @param packets paquets envoyés par passage ; à partir de 2, la sonde mesure aussi la perte et
   *     la gigue
   */
  @Autowired
  public IcmpProbe(@Value("${collection.probe.icmp.packets:4}") int packets) {
    this(new ProcessCommandRunner(), IcmpProbe::reachable, isWindows(), packets);
  }

  public IcmpProbe() {
    this(new ProcessCommandRunner());
  }

  public IcmpProbe(CommandRunner runner) {
    this(runner, IcmpProbe::reachable, isWindows());
  }

  public IcmpProbe(
      CommandRunner runner, BiPredicate<InetAddress, Duration> fallback, boolean windows) {
    this(runner, fallback, windows, 1);
  }

  public IcmpProbe(
      CommandRunner runner,
      BiPredicate<InetAddress, Duration> fallback,
      boolean windows,
      int packets) {
    this.runner = runner;
    this.fallback = fallback;
    this.windows = windows;
    this.packets = Math.max(1, Math.min(packets, 10));
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
    int count = packetsFor(request.timeout().toMillis());
    List<String> command = buildCommand(request.address(), request.timeout().toMillis(), count);
    long start = System.nanoTime();
    try {
      CommandOutput output = runner.run(command, request.timeout());
      if (!windows && output.exitCode() >= 2) {
        // iputils sort en 2 sur une erreur (« Operation not permitted » sans droit ICMP), en 1
        // seulement quand l'hôte ne répond pas : une erreur ne dit rien de l'hôte.
        return errorFallback(request, start);
      }
      if (output.exitCode() != 0) {
        return unreachable(count, "Hôte injoignable");
      }
      if (count > 1) {
        return quality(parseTimes(output.stdout()), count, start);
      }
      Integer latency = parseLatency(output.stdout());
      if (latency == null) {
        // Windows renvoie parfois 0 pour « hôte inaccessible » : sans temps, pas de réponse.
        return windows ? ProbeResult.down("Hôte injoignable") : ProbeResult.up(elapsedMs(start));
      }
      return ProbeResult.up(latency);
    } catch (InterruptedIOException e) {
      return unreachable(count, "Délai dépassé");
    } catch (IOException e) {
      return fallbackProbe(request, start);
    } catch (RuntimeException e) {
      return ProbeResult.down("Ping impossible");
    }
  }

  private static ProbeResult unreachable(int count, String error) {
    return count > 1 ? ProbeResult.downAllLost(error) : ProbeResult.down(error);
  }

  /**
   * Plusieurs paquets : latence moyenne, part de paquets sans réponse, et gigue (écart moyen entre
   * deux réponses successives). Une réponse compte si sa ligne porte un temps.
   */
  private ProbeResult quality(List<Double> times, int count, long start) {
    if (times.isEmpty()) {
      return windows
          ? ProbeResult.downAllLost("Hôte injoignable")
          : ProbeResult.upWithQuality(elapsedMs(start), 0, null);
    }
    int received = Math.min(times.size(), count);
    int loss = (int) Math.round((count - received) * 100.0 / count);
    double mean = times.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    Double jitter = null;
    if (times.size() >= 2) {
      double sum = 0;
      for (int i = 1; i < times.size(); i++) {
        sum += Math.abs(times.get(i) - times.get(i - 1));
      }
      jitter = Math.round(sum / (times.size() - 1) * 100.0) / 100.0;
    }
    return ProbeResult.upWithQuality(Math.max(1, (int) Math.round(mean)), loss, jitter);
  }

  /**
   * Nombre de paquets réellement envoyés : jamais plus que le délai ne permet d'attendre (une
   * seconde par paquet sous Linux), et trois au plus sous Windows.
   */
  int packetsFor(long timeoutMs) {
    if (packets == 1) {
      return 1;
    }
    if (windows) {
      return Math.min(packets, MAX_WINDOWS_PACKETS);
    }
    long seconds = Math.max(1L, (timeoutMs + 999L) / 1000L);
    return (int) Math.max(1L, Math.min(packets, seconds));
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

  /**
   * Ping lancé mais en erreur : repli sur {@code isReachable}. Un repli négatif ne prouve pas la
   * panne (sans droit ICMP, Java tente le port TCP 7, fermé presque partout) : résultat
   * indéterminé, qui ne compte pas comme un échec.
   */
  private ProbeResult errorFallback(ProbeRequest request, long start) {
    try {
      if (fallback.test(request.address(), request.timeout())) {
        return ProbeResult.up(elapsedMs(start));
      }
    } catch (RuntimeException e) {
      // repli inutilisable : même conclusion
    }
    return ProbeResult.indeterminate(PING_NOT_ALLOWED);
  }

  private List<String> buildCommand(InetAddress address, long timeoutMs, int count) {
    String ip = address.getHostAddress();
    int zone = ip.indexOf('%');
    if (zone >= 0) {
      ip = ip.substring(0, zone);
    }
    if (windows) {
      // Le délai est par paquet : on le partage pour que le passage tienne dans le délai demandé.
      long perPacket = count > 1 ? Math.max(200L, timeoutMs / count) : timeoutMs;
      return List.of(
          pingExecutable(true), "-n", Integer.toString(count), "-w", Long.toString(perPacket), ip);
    }
    long seconds = Math.max(1L, (timeoutMs + 999L) / 1000L);
    if (count == 1) {
      return List.of(pingExecutable(false), "-c", "1", "-W", Long.toString(seconds), ip);
    }
    // 0,2 s entre deux paquets : le plus court permis sans privilège par iputils.
    return List.of(
        pingExecutable(false),
        "-c",
        Integer.toString(count),
        "-i",
        "0.2",
        "-W",
        Long.toString(Math.max(1L, seconds / count)),
        ip);
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

  /** Temps de chaque réponse, dans l'ordre ; « <1 ms » compte pour 1 ms. */
  static List<Double> parseTimes(String stdout) {
    List<Double> times = new ArrayList<>();
    if (stdout == null) {
      return times;
    }
    Matcher m = LATENCY.matcher(stdout);
    while (m.find()) {
      if ("<".equals(m.group(1))) {
        times.add(1.0);
        continue;
      }
      try {
        times.add(Double.parseDouble(m.group(2).replace(',', '.')));
      } catch (NumberFormatException e) {
        // ligne illisible : ignorée
      }
    }
    return times;
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

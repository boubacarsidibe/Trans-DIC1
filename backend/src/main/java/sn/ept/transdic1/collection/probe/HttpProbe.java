package sn.ept.transdic1.collection.probe;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import sn.ept.transdic1.inventory.domain.ServiceType;

/**
 * Sonde HTTP/HTTPS : requête GET HTTP/1.1 écrite à la main sur un socket ouvert vers l'adresse déjà
 * validée (aucune résolution de nom), avec l'en-tête {@code Host} et le SNI du nom d'origine.
 *
 * <p>Redirections jamais suivies, corps lu au plus sur 64 Ko et jamais conservé, délai total unique
 * pour connexion + poignée de main TLS + requête + lecture. Certificat validé contre le nom d'hôte.
 */
public class HttpProbe implements Probe {
  /** Nombre maximal d'octets de corps lus (et examinés pour le texte attendu). */
  static final int MAX_BODY_BYTES = 64 * 1024;

  private static final int MAX_LINE_BYTES = 8 * 1024;
  private static final int MAX_HEAD_BYTES = 64 * 1024;
  private static final String DEFAULT_STATUS = "200-399";
  private static final Pattern STATUS_SPEC = Pattern.compile("(\\d{3})(?:-(\\d{3}))?");
  private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(?:\\.\\d{1,3}){3}");

  /** Garde de délai : ferme le socket à l'échéance (couvre aussi la poignée de main TLS). */
  private static final ScheduledThreadPoolExecutor WATCHDOG = newWatchdog();

  private final ServiceType type;
  private final SSLContext sslContext;

  public HttpProbe(ServiceType type, SSLContext sslContext) {
    if (type != ServiceType.HTTP && type != ServiceType.HTTPS) {
      throw new IllegalArgumentException("Type de sonde HTTP inattendu : " + type);
    }
    this.type = type;
    this.sslContext = sslContext;
  }

  @Override
  public ServiceType type() {
    return type;
  }

  @Override
  public ProbeResult run(ProbeRequest request) {
    String path = request.path() == null || request.path().isBlank() ? "/" : request.path();
    if (!isValidPath(path)) {
      return ProbeResult.down("Chemin invalide");
    }
    String host = request.host();
    if (!isValidHost(host)) {
      return ProbeResult.down("Hôte invalide");
    }
    int[] expected = parseExpectedStatus(request.expectedStatus());
    if (expected == null) {
      return ProbeResult.down("Statut attendu invalide");
    }
    boolean https = type == ServiceType.HTTPS;
    int defaultPort = https ? 443 : 80;
    int port = request.port() == null ? defaultPort : request.port();
    if (port < 1 || port > 65535) {
      return ProbeResult.down("Port invalide");
    }
    long timeoutNanos = request.timeout().toNanos();
    long start = System.nanoTime();
    long deadline = start + timeoutNanos;

    Socket plain = new Socket();
    Socket socket = plain;
    ScheduledFuture<?> guard =
        WATCHDOG.schedule(() -> closeQuietly(plain), timeoutNanos, TimeUnit.NANOSECONDS);
    try {
      // Adresse déjà résolue et validée : connexion uniquement à elle.
      plain.connect(new InetSocketAddress(request.address(), port), remainingMillis(deadline));
      Integer tlsDaysLeft = null;
      if (https) {
        SSLSocket ssl = openTls(plain, host, port, deadline);
        socket = ssl;
        tlsDaysLeft = daysLeft(ssl);
      }
      writeRequest(socket.getOutputStream(), path, host, port, defaultPort);
      DeadlineInputStream in = new DeadlineInputStream(socket, deadline);
      Response response = readHead(in);
      int latencyMs = (int) ((System.nanoTime() - start) / 1_000_000L);
      if (response.status < expected[0] || response.status > expected[1]) {
        return ProbeResult.down("Statut " + response.status + " inattendu");
      }
      String expectedText = request.expectedText();
      if (expectedText != null && !expectedText.isEmpty()) {
        byte[] body = readBody(in, response);
        if (!new String(body, StandardCharsets.UTF_8).contains(expectedText)) {
          return ProbeResult.down("Texte attendu absent");
        }
      }
      return https ? ProbeResult.up(latencyMs, tlsDaysLeft) : ProbeResult.up(latencyMs);
    } catch (InvalidResponseException e) {
      return ProbeResult.down(System.nanoTime() >= deadline ? "Délai dépassé" : "Réponse invalide");
    } catch (IOException | RuntimeException e) {
      return ProbeResult.down(classify(e, deadline));
    } finally {
      // Fermeture d'abord (close_notify TLS encore sous la garde du délai), puis annulation.
      closeQuietly(socket);
      closeQuietly(plain);
      guard.cancel(false);
    }
  }

  private SSLSocket openTls(Socket plain, String host, int port, long deadline) throws IOException {
    String peerHost = stripBrackets(host);
    SSLSocket ssl =
        (SSLSocket) sslContext.getSocketFactory().createSocket(plain, peerHost, port, true);
    SSLParameters parameters = ssl.getSSLParameters();
    parameters.setEndpointIdentificationAlgorithm("HTTPS");
    if (!isIpLiteral(peerHost)) {
      try {
        parameters.setServerNames(List.of(new SNIHostName(peerHost)));
      } catch (IllegalArgumentException e) {
        // nom non admissible en SNI : la vérification du certificat reste active
      }
    }
    ssl.setSSLParameters(parameters);
    ssl.setSoTimeout(remainingMillis(deadline));
    ssl.startHandshake();
    return ssl;
  }

  private static Integer daysLeft(SSLSocket ssl) throws IOException {
    if (ssl.getSession().getPeerCertificates()[0] instanceof X509Certificate certificate) {
      long seconds =
          Duration.between(Instant.now(), certificate.getNotAfter().toInstant()).getSeconds();
      return (int) Math.floorDiv(seconds, 86_400L);
    }
    return null;
  }

  private static void writeRequest(
      OutputStream out, String path, String host, int port, int defaultPort) throws IOException {
    String hostHeader = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
    if (port != defaultPort) {
      hostHeader += ":" + port;
    }
    String head =
        "GET "
            + path
            + " HTTP/1.1\r\n"
            + "Host: "
            + hostHeader
            + "\r\n"
            + "Connection: close\r\n"
            + "User-Agent: Trans-DIC1-Probe\r\n"
            + "\r\n";
    out.write(head.getBytes(StandardCharsets.US_ASCII));
    out.flush();
  }

  private static Response readHead(DeadlineInputStream in) throws IOException {
    int[] headBytes = {0};
    while (true) {
      String statusLine = readLine(in, headBytes);
      if (statusLine == null) {
        throw new InvalidResponseException();
      }
      int status = parseStatusLine(statusLine);
      Response response = new Response(status);
      String line;
      while ((line = readLine(in, headBytes)) != null && !line.isEmpty()) {
        int colon = line.indexOf(':');
        if (colon <= 0) {
          throw new InvalidResponseException();
        }
        String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
        String value = line.substring(colon + 1).trim();
        if (name.equals("content-length")) {
          try {
            response.contentLength = Long.parseLong(value);
          } catch (NumberFormatException e) {
            throw new InvalidResponseException();
          }
          if (response.contentLength < 0) {
            throw new InvalidResponseException();
          }
        } else if (name.equals("transfer-encoding")
            && value.toLowerCase(Locale.ROOT).contains("chunked")) {
          response.chunked = true;
        }
      }
      if (line == null) {
        throw new InvalidResponseException();
      }
      // Réponses intermédiaires 1xx (hors 101) : on passe à la réponse finale.
      if (status >= 100 && status < 200 && status != 101) {
        continue;
      }
      return response;
    }
  }

  private static int parseStatusLine(String line) throws InvalidResponseException {
    if (!line.startsWith("HTTP/1.") || line.length() < 12 || line.charAt(8) != ' ') {
      throw new InvalidResponseException();
    }
    String code = line.substring(9, 12);
    if (!code.chars().allMatch(c -> c >= '0' && c <= '9')
        || (line.length() > 12 && line.charAt(12) != ' ')) {
      throw new InvalidResponseException();
    }
    int status = Integer.parseInt(code);
    if (status < 100 || status > 599) {
      throw new InvalidResponseException();
    }
    return status;
  }

  private static byte[] readBody(DeadlineInputStream in, Response response) throws IOException {
    if (response.status == 204 || response.status == 304) {
      return new byte[0];
    }
    BoundedBuffer body = new BoundedBuffer(MAX_BODY_BYTES);
    if (response.chunked) {
      int[] lineBytes = {0};
      while (!body.full()) {
        lineBytes[0] = 0;
        String sizeLine = readLine(in, lineBytes);
        if (sizeLine == null) {
          break;
        }
        int semicolon = sizeLine.indexOf(';');
        String hex = (semicolon >= 0 ? sizeLine.substring(0, semicolon) : sizeLine).trim();
        long size;
        try {
          size = Long.parseLong(hex, 16);
        } catch (NumberFormatException e) {
          throw new InvalidResponseException();
        }
        if (size < 0) {
          throw new InvalidResponseException();
        }
        if (size == 0) {
          break;
        }
        if (!body.readFrom(in, size)) {
          break;
        }
        if (!body.full()) {
          lineBytes[0] = 0;
          readLine(in, lineBytes); // CRLF qui termine le bloc
        }
      }
    } else if (response.contentLength >= 0) {
      body.readFrom(in, response.contentLength);
    } else {
      body.readFrom(in, Long.MAX_VALUE);
    }
    return body.toByteArray();
  }

  /** Lit une ligne terminée par CRLF (ou LF), en ISO-8859-1 ; {@code null} en fin de flux. */
  private static String readLine(DeadlineInputStream in, int[] consumed) throws IOException {
    StringBuilder line = new StringBuilder();
    int b;
    while ((b = in.read()) != -1) {
      consumed[0]++;
      if (consumed[0] > MAX_HEAD_BYTES || line.length() > MAX_LINE_BYTES) {
        throw new InvalidResponseException();
      }
      if (b == '\n') {
        int length = line.length();
        if (length > 0 && line.charAt(length - 1) == '\r') {
          line.setLength(length - 1);
        }
        return line.toString();
      }
      line.append((char) b);
    }
    return line.isEmpty() ? null : line.toString();
  }

  private static String classify(Exception e, long deadline) {
    if (e instanceof SocketTimeoutException || System.nanoTime() >= deadline) {
      return "Délai dépassé";
    }
    if (e instanceof SSLHandshakeException && hasCertificateCause(e)) {
      return "Certificat invalide";
    }
    if (e instanceof SSLException) {
      return hasCertificateCause(e) ? "Certificat invalide" : "Échec TLS";
    }
    if (e instanceof NoRouteToHostException) {
      return "Hôte injoignable";
    }
    if (e instanceof ConnectException) {
      String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
      return message.contains("timed out") ? "Délai dépassé" : "Connexion refusée";
    }
    return "Connexion impossible";
  }

  private static boolean hasCertificateCause(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof CertificateException
          || t instanceof java.security.cert.CertPathValidatorException) {
        return true;
      }
    }
    return false;
  }

  /** « 200-399 » ou un code seul ; {@code null} si la valeur est invalide. */
  static int[] parseExpectedStatus(String spec) {
    String value = spec == null || spec.isBlank() ? DEFAULT_STATUS : spec.trim();
    Matcher matcher = STATUS_SPEC.matcher(value);
    if (!matcher.matches()) {
      return null;
    }
    int min = Integer.parseInt(matcher.group(1));
    int max = matcher.group(2) == null ? min : Integer.parseInt(matcher.group(2));
    if (min < 100 || max > 599 || min > max) {
      return null;
    }
    return new int[] {min, max};
  }

  private static boolean isValidPath(String path) {
    if (!path.startsWith("/")) {
      return false;
    }
    // ASCII imprimable sans espace : aucune injection de ligne ou d'en-tête possible.
    return path.chars().allMatch(c -> c > 0x20 && c < 0x7F);
  }

  private static boolean isValidHost(String host) {
    if (host == null || host.isEmpty() || host.length() > 255) {
      return false;
    }
    return host.chars()
        .allMatch(
            c ->
                (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '.'
                    || c == '-'
                    || c == '_'
                    || c == ':'
                    || c == '['
                    || c == ']'
                    || c == '%');
  }

  private static String stripBrackets(String host) {
    return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
  }

  private static boolean isIpLiteral(String host) {
    return host.indexOf(':') >= 0 || IPV4.matcher(host).matches();
  }

  private static int remainingMillis(long deadline) throws SocketTimeoutException {
    long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
    if (remaining <= 0) {
      throw new SocketTimeoutException();
    }
    return (int) Math.min(Integer.MAX_VALUE, remaining);
  }

  private static void closeQuietly(Socket socket) {
    try {
      socket.close();
    } catch (IOException e) {
      // rien à faire
    }
  }

  private static ScheduledThreadPoolExecutor newWatchdog() {
    ScheduledThreadPoolExecutor executor =
        new ScheduledThreadPoolExecutor(
            1,
            runnable -> {
              Thread thread = new Thread(runnable, "http-probe-deadline");
              thread.setDaemon(true);
              return thread;
            });
    executor.setRemoveOnCancelPolicy(true);
    return executor;
  }

  private static final class Response {
    final int status;
    long contentLength = -1;
    boolean chunked;

    Response(int status) {
      this.status = status;
    }
  }

  /** Réponse mal formée ; message jamais exposé. */
  private static final class InvalidResponseException extends IOException {
    private static final long serialVersionUID = 1L;
  }

  /**
   * Flux du socket avec délai total : avant chaque lecture, le délai de lecture du socket est
   * réduit au temps restant ; à l'échéance, la lecture échoue.
   */
  private static final class DeadlineInputStream extends InputStream {
    private final Socket socket;
    private final InputStream raw;
    private final long deadline;
    private final byte[] buffer = new byte[8192];
    private int position;
    private int limit;

    DeadlineInputStream(Socket socket, long deadline) throws IOException {
      this.socket = socket;
      this.raw = socket.getInputStream();
      this.deadline = deadline;
    }

    private boolean fill() throws IOException {
      socket.setSoTimeout(remainingMillis(deadline));
      int n = raw.read(buffer);
      if (n <= 0) {
        return false;
      }
      position = 0;
      limit = n;
      return true;
    }

    @Override
    public int read() throws IOException {
      if (position >= limit && !fill()) {
        return -1;
      }
      return buffer[position++] & 0xFF;
    }

    @Override
    public int read(byte[] target, int offset, int length) throws IOException {
      if (length == 0) {
        return 0;
      }
      if (position >= limit && !fill()) {
        return -1;
      }
      int n = Math.min(length, limit - position);
      System.arraycopy(buffer, position, target, offset, n);
      position += n;
      return n;
    }
  }

  /** Tampon de corps plafonné : n'accepte jamais plus de {@code capacity} octets. */
  private static final class BoundedBuffer {
    private final byte[] data;
    private int size;

    BoundedBuffer(int capacity) {
      this.data = new byte[capacity];
    }

    boolean full() {
      return size >= data.length;
    }

    /** Lit jusqu'à {@code count} octets (dans la limite du plafond) ; faux si fin de flux. */
    boolean readFrom(InputStream in, long count) throws IOException {
      long wanted = Math.min(count, data.length - size);
      while (wanted > 0) {
        int n = in.read(data, size, (int) wanted);
        if (n < 0) {
          return false;
        }
        size += n;
        wanted -= n;
      }
      return true;
    }

    byte[] toByteArray() {
      return java.util.Arrays.copyOf(data, size);
    }
  }
}

package sn.ept.transdic1.collection.probe;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import sn.ept.transdic1.inventory.domain.ServiceType;

class HttpProbeTests {
  private static final Duration TIMEOUT = Duration.ofSeconds(3);
  private static InetAddress loopback;
  private static TestTls tls;

  private HttpServer server;

  @BeforeAll
  static void setUpAll() throws Exception {
    loopback = InetAddress.getByName("127.0.0.1");
    tls = TestTls.selfSigned();
  }

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.stop(0);
    }
  }

  private static HttpProbe http() throws Exception {
    return new HttpProbe(ServiceType.HTTP, SSLContext.getDefault());
  }

  private int startHttp(String path, HttpHandler handler) throws IOException {
    server = HttpServer.create(new InetSocketAddress(loopback, 0), 0);
    server.createContext(path, handler);
    server.start();
    return server.getAddress().getPort();
  }

  private int startHttps(String path, HttpHandler handler) throws IOException {
    HttpsServer https = HttpsServer.create(new InetSocketAddress(loopback, 0), 0);
    https.setHttpsConfigurator(new HttpsConfigurator(tls.serverContext()));
    https.createContext(path, handler);
    https.start();
    server = https;
    return https.getAddress().getPort();
  }

  private static HttpHandler respond(int status, String body) {
    return exchange -> {
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
      if (bytes.length > 0) {
        try (OutputStream out = exchange.getResponseBody()) {
          out.write(bytes);
        }
      }
      exchange.close();
    };
  }

  private static ProbeRequest request(
      String host, int port, String path, String expectedStatus, String expectedText) {
    return new ProbeRequest(loopback, host, port, path, TIMEOUT, expectedStatus, expectedText);
  }

  @Test
  void typeFollowsConstructor() throws Exception {
    assertThat(http().type()).isEqualTo(ServiceType.HTTP);
    assertThat(new HttpProbe(ServiceType.HTTPS, SSLContext.getDefault()).type())
        .isEqualTo(ServiceType.HTTPS);
  }

  @Test
  void upOn200() throws Exception {
    int port = startHttp("/", respond(200, "ok"));

    ProbeResult result = http().run(request("127.0.0.1", port, "/", null, null));

    assertThat(result.up()).isTrue();
    assertThat(result.latencyMs()).isNotNull().isGreaterThanOrEqualTo(0);
    assertThat(result.tlsDaysLeft()).isNull();
    assertThat(result.error()).isNull();
  }

  @Test
  void upOn204WhenExpected204() throws Exception {
    int port = startHttp("/", respond(204, ""));

    ProbeResult result = http().run(request("127.0.0.1", port, "/", "204", null));

    assertThat(result.up()).isTrue();
  }

  @Test
  void downOn500() throws Exception {
    int port = startHttp("/", respond(500, "boom"));

    ProbeResult result = http().run(request("127.0.0.1", port, "/", null, null));

    assertThat(result.up()).isFalse();
    assertThat(result.error()).isEqualTo("Statut 500 inattendu");
  }

  @Test
  void redirectIsNotFollowed() throws Exception {
    AtomicInteger targetHits = new AtomicInteger();
    int port =
        startHttp(
            "/",
            exchange -> {
              if (exchange.getRequestURI().getPath().equals("/new")) {
                targetHits.incrementAndGet();
                respond(200, "new").handle(exchange);
                return;
              }
              exchange.getResponseHeaders().add("Location", "/new");
              respond(302, "").handle(exchange);
            });

    ProbeResult strict = http().run(request("127.0.0.1", port, "/old", "200-299", null));
    ProbeResult lenient = http().run(request("127.0.0.1", port, "/old", null, null));

    assertThat(strict.up()).isFalse();
    assertThat(strict.error()).isEqualTo("Statut 302 inattendu");
    assertThat(lenient.up()).isTrue();
    assertThat(targetHits.get()).isZero();
  }

  @Test
  void downWhenExpectedTextMissing() throws Exception {
    int port = startHttp("/", respond(200, "Bienvenue sur le portail"));

    ProbeResult result = http().run(request("127.0.0.1", port, "/", null, "Connexion"));

    assertThat(result.up()).isFalse();
    assertThat(result.error()).isEqualTo("Texte attendu absent");
  }

  @Test
  void upWhenExpectedTextPresent() throws Exception {
    int port = startHttp("/", respond(200, "Bienvenue sur le portail de connexion"));

    ProbeResult result = http().run(request("127.0.0.1", port, "/", null, "portail"));

    assertThat(result.up()).isTrue();
  }

  @Test
  void expectedTextIsFoundInChunkedBody() throws Exception {
    int port =
        startHttp(
            "/",
            exchange -> {
              exchange.sendResponseHeaders(200, 0); // 0 = chunked
              try (OutputStream out = exchange.getResponseBody()) {
                out.write("Bienvenue sur le por".getBytes(StandardCharsets.UTF_8));
                out.flush();
                out.write("tail de connexion".getBytes(StandardCharsets.UTF_8));
              }
              exchange.close();
            });

    ProbeResult result = http().run(request("127.0.0.1", port, "/", null, "portail"));

    assertThat(result.up()).isTrue();
  }

  @Test
  void sendsHostHeaderOfOriginalName() throws Exception {
    AtomicReference<String> host = new AtomicReference<>();
    AtomicReference<String> userAgent = new AtomicReference<>();
    int port =
        startHttp(
            "/",
            exchange -> {
              host.set(exchange.getRequestHeaders().getFirst("Host"));
              userAgent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
              respond(200, "ok").handle(exchange);
            });

    ProbeResult result = http().run(request("app.example", port, "/sante", null, null));

    assertThat(result.up()).isTrue();
    assertThat(host.get()).isEqualTo("app.example:" + port);
    assertThat(userAgent.get()).isEqualTo("Trans-DIC1-Probe");
  }

  @Test
  void downWhenConnectionRefused() throws Exception {
    int closedPort;
    try (ServerSocket socket = new ServerSocket(0, 1, loopback)) {
      closedPort = socket.getLocalPort();
    }

    ProbeResult result = http().run(request("127.0.0.1", closedPort, "/", null, null));

    assertThat(result.up()).isFalse();
    assertThat(result.error()).isEqualTo("Connexion refusée");
  }

  @Test
  void readsAtMost64Ko() throws Exception {
    AtomicLong written = new AtomicLong();
    try (ServerSocket listener = new ServerSocket(0, 1, loopback)) {
      Thread serverThread =
          Thread.ofPlatform()
              .start(
                  () -> {
                    try (Socket socket = listener.accept()) {
                      socket.setSendBufferSize(4096);
                      readRequestHead(socket.getInputStream());
                      OutputStream out = socket.getOutputStream();
                      int total = 10 * 1024 * 1024;
                      out.write(
                          ("HTTP/1.1 200 OK\r\nContent-Length: " + total + "\r\n\r\n")
                              .getBytes(StandardCharsets.US_ASCII));
                      byte[] chunk = new byte[1024];
                      java.util.Arrays.fill(chunk, (byte) 'a');
                      for (int sent = 0; sent < total; sent += chunk.length) {
                        out.write(chunk);
                        written.addAndGet(chunk.length);
                      }
                    } catch (IOException expected) {
                      // le client a coupé : c'est le comportement attendu
                    }
                  });

      ProbeResult result =
          http().run(request("127.0.0.1", listener.getLocalPort(), "/", null, "introuvable"));
      serverThread.join(5000);

      assertThat(result.up()).isFalse();
      assertThat(result.error()).isEqualTo("Texte attendu absent");
      // 64 Ko lus par la sonde + tampons TCP (émission et réception) du système, environ 200 Ko
      // mesurés en local ; une lecture complète enverrait 10 Mo.
      assertThat(written.get()).isLessThan(64 * 1024 + 512 * 1024);
    }
  }

  @Test
  void totalTimeoutCutsSlowDrip() throws Exception {
    try (ServerSocket listener = new ServerSocket(0, 1, loopback)) {
      Thread serverThread =
          Thread.ofPlatform()
              .start(
                  () -> {
                    try (Socket socket = listener.accept()) {
                      readRequestHead(socket.getInputStream());
                      OutputStream out = socket.getOutputStream();
                      byte[] response =
                          "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok"
                              .getBytes(StandardCharsets.US_ASCII);
                      for (byte b : response) {
                        out.write(b);
                        out.flush();
                        Thread.sleep(100);
                      }
                    } catch (IOException | InterruptedException expected) {
                      // le client a coupé : c'est le comportement attendu
                    }
                  });

      long start = System.nanoTime();
      ProbeResult result =
          http()
              .run(
                  new ProbeRequest(
                      loopback,
                      "127.0.0.1",
                      listener.getLocalPort(),
                      "/",
                      Duration.ofMillis(500),
                      null,
                      null));
      long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
      serverThread.join(5000);

      assertThat(result.up()).isFalse();
      assertThat(result.error()).isEqualTo("Délai dépassé");
      assertThat(elapsedMs).isLessThan(1000);
    }
  }

  @Test
  void httpsInvalidChainIsDown() throws Exception {
    int port = startHttps("/", respond(200, "ok"));
    HttpProbe probe = new HttpProbe(ServiceType.HTTPS, SSLContext.getDefault());

    ProbeResult result = probe.run(request("localhost", port, "/", null, null));

    assertThat(result.up()).isFalse();
    assertThat(result.error()).isEqualTo("Certificat invalide");
  }

  @Test
  void httpsTrustedChainIsUpWithDaysLeft() throws Exception {
    int port = startHttps("/", respond(200, "ok"));
    HttpProbe probe = new HttpProbe(ServiceType.HTTPS, tls.clientContextTrustingThis());

    ProbeResult result = probe.run(request("localhost", port, "/", null, "ok"));

    assertThat(result.up()).isTrue();
    assertThat(result.error()).isNull();
    assertThat(result.tlsDaysLeft()).isBetween(363, 365);
  }

  @Test
  void httpsHostnameMismatchIsDown() throws Exception {
    int port = startHttps("/", respond(200, "ok"));
    HttpProbe probe = new HttpProbe(ServiceType.HTTPS, tls.clientContextTrustingThis());

    ProbeResult result = probe.run(request("autre.example", port, "/", null, null));

    assertThat(result.up()).isFalse();
    assertThat(result.error()).isEqualTo("Certificat invalide");
  }

  @Test
  void rejectsPathWithLineBreak() throws Exception {
    int port = startHttp("/", respond(200, "ok"));

    ProbeResult result = http().run(request("127.0.0.1", port, "/\r\nX-Injecte: 1", null, null));

    assertThat(result.up()).isFalse();
    assertThat(result.error()).isEqualTo("Chemin invalide");
  }

  private static void readRequestHead(InputStream in) throws IOException {
    int matched = 0;
    byte[] end = {'\r', '\n', '\r', '\n'};
    int b;
    while (matched < end.length && (b = in.read()) != -1) {
      matched = b == end[matched] ? matched + 1 : (b == '\r' ? 1 : 0);
    }
  }
}

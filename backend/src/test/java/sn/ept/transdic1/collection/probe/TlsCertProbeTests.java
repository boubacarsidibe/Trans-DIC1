package sn.ept.transdic1.collection.probe;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import sn.ept.transdic1.inventory.domain.ServiceType;

class TlsCertProbeTests {
  private static final String SAN = "dns:localhost,ip:127.0.0.1";
  private static InetAddress loopback;

  private ServerSocket server;
  private Thread acceptor;

  @BeforeAll
  static void setUpAll() throws Exception {
    loopback = InetAddress.getByName("127.0.0.1");
  }

  @AfterEach
  void tearDown() throws IOException {
    if (server != null) {
      server.close();
    }
    if (acceptor != null) {
      acceptor.interrupt();
    }
  }

  private int startTlsServer(TestTls tls) throws IOException {
    SSLServerSocket tlsServer =
        (SSLServerSocket)
            tls.serverContext().getServerSocketFactory().createServerSocket(0, 5, loopback);
    server = tlsServer;
    acceptor = daemon(() -> acceptLoop(tlsServer, true));
    return tlsServer.getLocalPort();
  }

  private int startPlainServer() throws IOException {
    ServerSocket plain = new ServerSocket(0, 5, loopback);
    server = plain;
    acceptor = daemon(() -> acceptLoop(plain, false));
    return plain.getLocalPort();
  }

  private static Thread daemon(Runnable runnable) {
    Thread thread = new Thread(runnable, "tls-test-server");
    thread.setDaemon(true);
    thread.start();
    return thread;
  }

  private static void acceptLoop(ServerSocket socket, boolean tls) {
    while (!socket.isClosed()) {
      try (Socket client = socket.accept()) {
        if (tls) {
          ((SSLSocket) client).startHandshake();
        } else {
          client.getOutputStream().write("pas de TLS ici\r\n".getBytes());
          client.getOutputStream().flush();
          client.getInputStream().read();
        }
      } catch (IOException e) {
        // client parti ou serveur fermé
      }
    }
  }

  private static ProbeRequest request(int port) {
    return new ProbeRequest(loopback, "localhost", port, null, Duration.ofSeconds(3), null, null);
  }

  @Test
  void typeIsTlsCert() {
    assertThat(new TlsCertProbe().type()).isEqualTo(ServiceType.TLS_CERT);
  }

  @Test
  void daysLeftForCertValidTenDays() throws Exception {
    int port = startTlsServer(TestTls.generate(SAN, null, 10));

    ProbeResult result = new TlsCertProbe().run(request(port));

    assertThat(result.up()).isTrue();
    assertThat(result.error()).isNull();
    assertThat(result.latencyMs()).isNotNull().isGreaterThanOrEqualTo(0);
    assertThat(result.tlsDaysLeft()).isBetween(9, 10);
  }

  @Test
  void expiredCertGivesNegativeDays() throws Exception {
    int port = startTlsServer(TestTls.generate(SAN, "-20d", 10));

    ProbeResult result = new TlsCertProbe().run(request(port));

    assertThat(result.up()).isTrue();
    assertThat(result.tlsDaysLeft()).isNotNull().isNegative();
    assertThat(result.tlsDaysLeft()).isBetween(-11, -10);
  }

  @Test
  void selfSignedCertStillReadable() throws Exception {
    int port = startTlsServer(TestTls.selfSigned());

    ProbeResult result = new TlsCertProbe().run(request(port));

    assertThat(result.up()).isTrue();
    assertThat(result.tlsDaysLeft()).isBetween(363, 365);
  }

  @Test
  void downWhenNoTlsOnPort() throws Exception {
    int port = startPlainServer();

    ProbeResult result = new TlsCertProbe().run(request(port));

    assertThat(result.up()).isFalse();
    assertThat(result.error()).isEqualTo("Poignée de main TLS impossible");
  }

  @Test
  void downWhenConnectionRefused() throws Exception {
    int port;
    try (ServerSocket closed = new ServerSocket(0, 1, loopback)) {
      port = closed.getLocalPort();
    }

    ProbeResult result = new TlsCertProbe().run(request(port));

    assertThat(result.up()).isFalse();
    assertThat(result.error()).isEqualTo("Connexion refusée");
  }

  @Test
  void downWhenServerNeverAnswersWithinTotalDeadline() throws Exception {
    server = new ServerSocket(0, 5, loopback); // accepte au niveau TCP, ne parle jamais
    int port = server.getLocalPort();
    ProbeRequest slow =
        new ProbeRequest(loopback, "localhost", port, null, Duration.ofMillis(500), null, null);

    long start = System.nanoTime();
    ProbeResult result = new TlsCertProbe().run(slow);
    long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

    assertThat(result.up()).isFalse();
    assertThat(result.error()).isEqualTo("Délai dépassé");
    assertThat(elapsedMs).isLessThan(2500);
  }
}

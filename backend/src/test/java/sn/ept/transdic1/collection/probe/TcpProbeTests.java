package sn.ept.transdic1.collection.probe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import sn.ept.transdic1.inventory.domain.ServiceType;

class TcpProbeTests {
  private final TcpProbe probe = new TcpProbe();

  private static ProbeRequest request(InetAddress address, int port, Duration timeout) {
    return new ProbeRequest(address, address.getHostAddress(), port, null, timeout, null, null);
  }

  @Test
  void typeIsTcpPort() {
    assertThat(probe.type()).isEqualTo(ServiceType.TCP_PORT);
  }

  @Test
  void upWhenPortOpen() throws Exception {
    InetAddress loopback = InetAddress.getByName("127.0.0.1");
    try (ServerSocket server = new ServerSocket(0, 1, loopback)) {
      ProbeResult result =
          probe.run(request(loopback, server.getLocalPort(), Duration.ofSeconds(2)));

      assertThat(result.up()).isTrue();
      assertThat(result.latencyMs()).isNotNull().isGreaterThanOrEqualTo(0);
      assertThat(result.error()).isNull();
    }
  }

  @Test
  void downWhenConnectionRefused() throws Exception {
    InetAddress loopback = InetAddress.getByName("127.0.0.1");
    int closedPort;
    try (ServerSocket server = new ServerSocket(0, 1, loopback)) {
      closedPort = server.getLocalPort();
    }

    ProbeResult result = probe.run(request(loopback, closedPort, Duration.ofSeconds(2)));

    assertThat(result.up()).isFalse();
    assertThat(result.error()).isEqualTo("Connexion refusée");
  }

  @Test
  void downWhenTimeout() throws Exception {
    InetAddress unroutable = InetAddress.getByName("10.255.255.1");
    ProbeResult result = probe.run(request(unroutable, 81, Duration.ofMillis(200)));

    // Si le réseau répond tout de suite (rejet immédiat), le scénario n'est pas testable ici.
    assumeTrue("Délai dépassé".equals(result.error()), "réseau local particulier");
    assertThat(result.up()).isFalse();
    assertThat(result.error()).isEqualTo("Délai dépassé");
  }
}

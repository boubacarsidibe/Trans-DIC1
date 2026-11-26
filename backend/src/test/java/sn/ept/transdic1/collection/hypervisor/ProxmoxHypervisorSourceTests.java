package sn.ept.transdic1.collection.hypervisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sn.ept.transdic1.collection.port.HypervisorEndpoint;
import sn.ept.transdic1.collection.port.HypervisorInventory;
import sn.ept.transdic1.collection.port.HypervisorSourceException;
import sn.ept.transdic1.collection.port.HypervisorSourceException.Reason;
import sn.ept.transdic1.inventory.domain.HypervisorResourceKind;
import sn.ept.transdic1.inventory.domain.PowerState;

/**
 * Connecteur Proxmox contre un faux serveur HTTPS qui rejoue des réponses enregistrées
 * (src/test/resources/proxmox). Le certificat du faux serveur est généré au lancement par keytool :
 * aucune clé n'est versionnée.
 */
class ProxmoxHypervisorSourceTests {
  private static final String TOKEN_ID = "supervision@pve!trans-dic1";
  private static final String SECRET = "00000000-test-0000-secret-000000000000";

  private static HttpsServer server;
  private static String caPem;
  private static URI base;
  private static final Map<String, Reply> replies = new ConcurrentHashMap<>();
  private static final CopyOnWriteArrayList<String> authorizations = new CopyOnWriteArrayList<>();

  private final ProxmoxHypervisorSource source = new ProxmoxHypervisorSource();

  record Reply(int status, String body, long delayMs) {}

  @BeforeAll
  static void start() throws Exception {
    Path folder = Files.createTempDirectory("tdic1-pve-");
    Path store = folder.resolve("pve.p12");
    Path pem = folder.resolve("pve.pem");
    String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
    run(
        keytool,
        "-genkeypair",
        "-alias",
        "pve",
        "-keyalg",
        "EC",
        "-groupname",
        "secp256r1",
        "-dname",
        "CN=127.0.0.1",
        "-ext",
        "SAN=ip:127.0.0.1,dns:localhost",
        "-validity",
        "2",
        "-keystore",
        store.toString(),
        "-storetype",
        "PKCS12",
        "-storepass",
        "changeit",
        "-keypass",
        "changeit");
    run(
        keytool,
        "-exportcert",
        "-rfc",
        "-alias",
        "pve",
        "-keystore",
        store.toString(),
        "-storepass",
        "changeit",
        "-file",
        pem.toString());
    caPem = Files.readString(pem);

    KeyStore keys = KeyStore.getInstance("PKCS12");
    try (InputStream in = new FileInputStream(store.toFile())) {
      keys.load(in, "changeit".toCharArray());
    }
    KeyManagerFactory managers =
        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    managers.init(keys, "changeit".toCharArray());
    SSLContext tls = SSLContext.getInstance("TLS");
    tls.init(managers.getKeyManagers(), null, null);

    server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setHttpsConfigurator(new HttpsConfigurator(tls));
    server.createContext(
        "/",
        exchange -> {
          authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
          Reply reply =
              replies.getOrDefault(exchange.getRequestURI().getPath(), new Reply(404, "{}", 0));
          if (reply.delayMs() > 0) {
            try {
              Thread.sleep(reply.delayMs());
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
          byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          try {
            exchange.sendResponseHeaders(reply.status(), body.length);
            exchange.getResponseBody().write(body);
          } catch (IOException e) {
            // Le client a abandonné (essai de délai dépassé) : rien à faire.
          } finally {
            exchange.close();
          }
        });
    server.start();
    base = URI.create("https://127.0.0.1:" + server.getAddress().getPort());
  }

  @AfterAll
  static void stop() {
    server.stop(0);
  }

  @BeforeEach
  void reset() throws IOException {
    replies.clear();
    authorizations.clear();
    replies.put(
        "/api2/json/cluster/resources", new Reply(200, fixture("cluster-resources.json"), 0));
    replies.put("/api2/json/cluster/status", new Reply(200, fixture("cluster-status.json"), 0));
    replies.put(
        "/api2/json/nodes/pve-01/qemu/101/agent/network-get-interfaces",
        new Reply(200, fixture("qemu-101-agent.json"), 0));
    replies.put(
        "/api2/json/nodes/pve-01/qemu/101/config",
        new Reply(200, fixture("qemu-101-config.json"), 0));
    replies.put(
        "/api2/json/nodes/pve-01/lxc/201/interfaces",
        new Reply(200, fixture("lxc-201-interfaces.json"), 0));
  }

  private static void run(String... command) throws Exception {
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    if (process.waitFor() != 0) {
      throw new IllegalStateException("keytool a échoué : " + output);
    }
  }

  private static String fixture(String name) throws IOException {
    try (InputStream in =
        ProxmoxHypervisorSourceTests.class.getResourceAsStream("/proxmox/" + name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static HypervisorEndpoint endpoint(String ca, Duration timeout) {
    return new HypervisorEndpoint(base, TOKEN_ID, SECRET, ca, timeout);
  }

  private static HypervisorEndpoint trusted() {
    return endpoint(caPem, Duration.ofSeconds(5));
  }

  private static HypervisorInventory.Guest guest(String id, HypervisorResourceKind kind) {
    return new HypervisorInventory.Guest(
        id, id, kind, "node/pve-01", PowerState.RUNNING, false, null, null, null, null, null, null);
  }

  @Test
  @DisplayName("Lit hôtes et invités, avec états, charges, adresses des hôtes, modèles signalés")
  void readsHostsAndGuests() throws Exception {
    HypervisorInventory inventory = source.read(trusted());

    assertThat(inventory.hosts()).hasSize(2);
    var pve01 = inventory.hosts().get(0);
    assertThat(pve01.externalId()).isEqualTo("node/pve-01");
    assertThat(pve01.power()).isEqualTo(PowerState.ONLINE);
    assertThat(pve01.address()).isEqualTo("192.0.2.11");
    assertThat(pve01.cpuPercent()).isEqualTo(12.3);
    assertThat(pve01.memoryPercent()).isEqualTo(50.0);
    assertThat(pve01.diskPercent()).isEqualTo(20.0);
    assertThat(inventory.hosts().get(1).power()).isEqualTo(PowerState.OFFLINE);

    assertThat(inventory.guests())
        .extracting(HypervisorInventory.Guest::externalId)
        .containsExactly("qemu/101", "qemu/102", "qemu/103", "lxc/201", "qemu/9000");
    var web = inventory.guests().get(0);
    assertThat(web.kind()).isEqualTo(HypervisorResourceKind.VM);
    assertThat(web.hostExternalId()).isEqualTo("node/pve-01");
    assertThat(web.power()).isEqualTo(PowerState.RUNNING);
    assertThat(web.cpuPercent()).isEqualTo(25.0);
    assertThat(web.memoryPercent()).isEqualTo(25.0);
    // Disque d'une VM inconnu sans agent invité : pas de faux zéro.
    assertThat(web.diskPercent()).isNull();
    assertThat(web.netInBytes()).isEqualTo(1_000_000L);
    assertThat(inventory.guests().get(1).power()).isEqualTo(PowerState.STOPPED);
    assertThat(inventory.guests().get(2).power()).isEqualTo(PowerState.UNKNOWN);
    var dns = inventory.guests().get(3);
    assertThat(dns.kind()).isEqualTo(HypervisorResourceKind.CONTAINER);
    assertThat(dns.diskPercent()).isEqualTo(12.5);
    assertThat(inventory.guests().get(4).template()).isTrue();

    assertThat(authorizations).allMatch(h -> h.equals("PVEAPIToken=" + TOKEN_ID + "=" + SECRET));
  }

  @Test
  @DisplayName("Adresses de l'agent invité : ni boucle locale ni lien local, IPv4 d'abord")
  void guestAddressesWithoutLoopbackIpv4First() throws Exception {
    assertThat(source.guestAddresses(trusted(), guest("qemu/101", HypervisorResourceKind.VM)))
        .containsExactly("192.0.2.101", "2001:db8::101");
    assertThat(source.guestAddresses(trusted(), guest("lxc/201", HypervisorResourceKind.CONTAINER)))
        .containsExactly("192.0.2.201", "2001:db8::201");
  }

  @Test
  @DisplayName("Agent invité arrêté ou droit absent : adresse inconnue, pas une panne")
  void stoppedGuestAgentGivesNoAddress() throws Exception {
    replies.put(
        "/api2/json/nodes/pve-01/qemu/101/agent/network-get-interfaces",
        new Reply(500, "{\"data\":null,\"message\":\"QEMU guest agent is not running\"}", 0));
    assertThat(source.guestAddresses(trusted(), guest("qemu/101", HypervisorResourceKind.VM)))
        .isEmpty();
    replies.put("/api2/json/nodes/pve-01/qemu/101/config", new Reply(403, "{}", 0));
    assertThat(source.guestUuid(trusted(), guest("qemu/101", HypervisorResourceKind.VM))).isEmpty();
  }

  @Test
  @DisplayName("L'UUID SMBIOS d'une VM est lu dans sa configuration")
  void readsTheSmbiosUuid() throws Exception {
    assertThat(source.guestUuid(trusted(), guest("qemu/101", HypervisorResourceKind.VM)))
        .contains("5d2c9e1a-7b3f-4c8d-9e0f-1a2b3c4d5e6f");
    assertThat(source.guestUuid(trusted(), guest("lxc/201", HypervisorResourceKind.CONTAINER)))
        .isEmpty();
  }

  @Test
  @DisplayName("Un invité mal identifié n'est jamais glissé dans une URL")
  void refusesMalformedGuestIdentifiers() {
    var forged =
        new HypervisorInventory.Guest(
            "qemu/101/../../access",
            "x",
            HypervisorResourceKind.VM,
            "node/pve-01",
            PowerState.RUNNING,
            false,
            null,
            null,
            null,
            null,
            null,
            null);
    assertThatThrownBy(() -> source.guestAddresses(trusted(), forged))
        .isInstanceOf(HypervisorSourceException.class);
  }

  @Test
  @DisplayName("Certificat refusé quand l'autorité n'est pas fournie")
  void certificateRefusedWithoutTheInternalAuthority() {
    assertThatThrownBy(() -> source.read(endpoint(null, Duration.ofSeconds(5))))
        .isInstanceOfSatisfying(
            HypervisorSourceException.class,
            e -> {
              assertThat(e.reason()).isEqualTo(Reason.CERTIFICATE_REFUSED);
              assertThat(e.meansUnreachable()).isFalse();
              assertThat(e.getMessage()).doesNotContain(SECRET);
            });
  }

  @Test
  @DisplayName("Jeton refusé (401) et droits insuffisants (403) : erreur de configuration")
  void tokenRefused() {
    replies.put("/api2/json/cluster/resources", new Reply(401, "{\"data\":null}", 0));
    assertThatThrownBy(() -> source.read(trusted()))
        .isInstanceOfSatisfying(
            HypervisorSourceException.class,
            e -> {
              assertThat(e.reason()).isEqualTo(Reason.AUTH_REFUSED);
              assertThat(e.getMessage()).contains("401").doesNotContain(SECRET);
            });
    replies.put("/api2/json/cluster/resources", new Reply(403, "{\"data\":null}", 0));
    assertThatThrownBy(() -> source.read(trusted())).hasMessageContaining("PVEAuditor");
  }

  @Test
  @DisplayName("Délai dépassé : l'hyperviseur est compté injoignable")
  void timeout() {
    replies.put("/api2/json/cluster/resources", new Reply(200, "{\"data\":[]}", 2_000));
    assertThatThrownBy(() -> source.read(endpoint(caPem, Duration.ofMillis(300))))
        .isInstanceOfSatisfying(
            HypervisorSourceException.class,
            e -> {
              assertThat(e.reason()).isEqualTo(Reason.TIMEOUT);
              assertThat(e.meansUnreachable()).isTrue();
            });
  }

  @Test
  @DisplayName("Réponse invalide : JSON illisible, champ data absent, liste attendue")
  void invalidResponses() {
    for (String body :
        new String[] {"<html>pas du JSON</html>", "{\"errors\":{}}", "{\"data\":{}}"}) {
      replies.put("/api2/json/cluster/resources", new Reply(200, body, 0));
      assertThatThrownBy(() -> source.read(trusted()))
          .isInstanceOfSatisfying(
              HypervisorSourceException.class,
              e -> assertThat(e.reason()).isEqualTo(Reason.INVALID_RESPONSE));
    }
    replies.put("/api2/json/cluster/resources", new Reply(302, "{}", 0));
    assertThatThrownBy(() -> source.read(trusted()))
        .isInstanceOfSatisfying(
            HypervisorSourceException.class,
            e -> assertThat(e.reason()).isEqualTo(Reason.INVALID_RESPONSE));
  }

  @Test
  @DisplayName("Erreur du serveur et port fermé : injoignable")
  void serverErrorAndClosedPortMeanUnreachable() throws Exception {
    replies.put("/api2/json/cluster/resources", new Reply(503, "{}", 0));
    assertThatThrownBy(() -> source.read(trusted()))
        .isInstanceOfSatisfying(
            HypervisorSourceException.class, e -> assertThat(e.meansUnreachable()).isTrue());

    int closed;
    try (ServerSocket socket = new ServerSocket(0)) {
      closed = socket.getLocalPort();
    }
    var nowhere =
        new HypervisorEndpoint(
            URI.create("https://127.0.0.1:" + closed),
            TOKEN_ID,
            SECRET,
            caPem,
            Duration.ofSeconds(2));
    assertThatThrownBy(() -> source.read(nowhere))
        .isInstanceOfSatisfying(
            HypervisorSourceException.class,
            e -> assertThat(e.reason()).isEqualTo(Reason.UNREACHABLE));
  }

  @Test
  @DisplayName("Sans droit sur /cluster/status, l'inventaire reste lisible, sans adresses d'hôtes")
  void clusterStatusIsOptional() throws Exception {
    replies.put("/api2/json/cluster/status", new Reply(403, "{}", 0));
    assertThat(source.read(trusted()).hosts().get(0).address()).isNull();
  }

  @Test
  @DisplayName(
      "Autorité de certification : un PEM illisible est refusé, les clients sont réutilisés")
  void authorityChecksAndClientReuse() throws Exception {
    assertThatThrownBy(
            () ->
                ProxmoxHypervisorSource.sslContext(
                    "-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----"))
        .isInstanceOf(Exception.class);
    assertThat(ProxmoxHypervisorSource.sslContext(null)).isNotNull();
    source.read(trusted());
    source.read(trusted());
    assertThat(source.cachedClients()).isEqualTo(1);
    assertThat(trusted().toString()).doesNotContain(SECRET);
  }
}

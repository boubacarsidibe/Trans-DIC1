package sn.ept.transdic1.collection.probe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Sonde DNS contre un faux serveur de noms qui répond vraiment en UDP : résolution réussie, adresse
 * attendue, nom inconnu, silence et réponse illisible.
 */
class DnsProbeTests {
  private FakeNameServer server;

  @AfterEach
  void stop() {
    if (server != null) {
      server.close();
    }
  }

  private ProbeResult probe(String name, String expected) throws Exception {
    return new DnsProbe()
        .run(
            new ProbeRequest(
                InetAddress.getByName("127.0.0.1"),
                "127.0.0.1",
                server.port(),
                name,
                Duration.ofSeconds(2),
                null,
                expected));
  }

  @Test
  void uneResolutionReussieRendLaSondeEnLigneAvecSonTemps() throws Exception {
    server = new FakeNameServer(id -> answer(id, List.of("10.1.2.3")));

    ProbeResult result = probe("ent.ept.sn", null);

    assertThat(result.up()).isTrue();
    assertThat(result.latencyMs()).isNotNull().isPositive();
    assertThat(result.error()).isNull();
  }

  @Test
  void lAdresseAttendueEstVerifiee() throws Exception {
    server = new FakeNameServer(id -> answer(id, List.of("10.1.2.3")));

    assertThat(probe("ent.ept.sn", "10.1.2.3").up()).isTrue();
    assertThat(probe("ent.ept.sn", " 10.1.2.3 ").up()).isTrue();

    ProbeResult wrong = probe("ent.ept.sn", "10.9.9.9");
    assertThat(wrong.up()).isFalse();
    assertThat(wrong.error()).isEqualTo("Le nom ne résout pas vers l'adresse attendue");
  }

  @Test
  void unNomInconnuEstUnePanneExpliquee() throws Exception {
    // Code de retour 3 : le serveur répond, mais le nom n'existe pas.
    server = new FakeNameServer(id -> header(id, 3, 0));

    ProbeResult result = probe("absent.ept.sn", null);

    assertThat(result.up()).isFalse();
    assertThat(result.error()).isEqualTo("Nom inconnu du serveur de noms");
  }

  @Test
  void unServeurQuiRepondSansAdresseEstUnePanne() throws Exception {
    server = new FakeNameServer(id -> header(id, 0, 0));

    assertThat(probe("ent.ept.sn", null).error())
        .isEqualTo("Le serveur de noms n'a rendu aucune adresse");
  }

  @Test
  void unServeurMuetDonneUnDelaiDepasse() throws Exception {
    server = new FakeNameServer(id -> null);

    ProbeResult result =
        new DnsProbe()
            .run(
                new ProbeRequest(
                    InetAddress.getByName("127.0.0.1"),
                    "127.0.0.1",
                    server.port(),
                    "ent.ept.sn",
                    Duration.ofMillis(300),
                    null,
                    null));

    assertThat(result.up()).isFalse();
    assertThat(result.error()).isEqualTo("Délai dépassé");
  }

  @Test
  void uneReponseIllisibleNeFaitPasTomberLaSonde() throws Exception {
    server = new FakeNameServer(id -> new byte[] {1, 2, 3});

    assertThat(probe("ent.ept.sn", null).error()).isEqualTo("Réponse du serveur de noms illisible");
  }

  @Test
  void unNomAResoudreManquantOuInvalideEstRefuseSansReseau() throws Exception {
    server = new FakeNameServer(id -> answer(id, List.of("10.1.2.3")));

    assertThat(probe(null, null).error()).isEqualTo("Nom à résoudre manquant");
    assertThat(probe("   ", null).error()).isEqualTo("Nom à résoudre manquant");
    assertThat(probe("x".repeat(80) + ".ept.sn", null).error())
        .isEqualTo("Nom à résoudre invalide");
  }

  @Test
  void leNomEstCodeEnEtiquettes() {
    assertThat(DnsQuery.encodeName("ent.ept.sn"))
        .containsExactly(3, 'e', 'n', 't', 3, 'e', 'p', 't', 2, 's', 'n', 0);
    // Le point final est permis, la casse ne compte pas.
    assertThat(DnsQuery.encodeName("ENT.ept.sn.")).isEqualTo(DnsQuery.encodeName("ent.ept.sn"));
    assertThatThrownBy(() -> DnsQuery.encodeName("")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> DnsQuery.encodeName("a..b"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void uneReponseQuiNeRepondPasALaQuestionEstRefusee() {
    byte[] other = answer(1234, List.of("10.1.2.3"));

    assertThatThrownBy(() -> DnsQuery.read(other, other.length, 4321))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("autre question");
  }

  // ---------------------------------------------------------------- faux serveur

  /** En-tête DNS d'une réponse, sans question ni enregistrement. */
  private static byte[] header(int id, int responseCode, int answers) {
    var out = new ByteArrayOutputStream();
    write(out, id);
    write(out, 0x8180 | responseCode);
    write(out, 1); // la question est répétée
    write(out, answers);
    write(out, 0);
    write(out, 0);
    // Question : ent.ept.sn, type A, classe IN.
    out.writeBytes(DnsQuery.encodeName("ent.ept.sn"));
    write(out, 1);
    write(out, 1);
    return out.toByteArray();
  }

  /** Réponse complète avec des enregistrements A. */
  private static byte[] answer(int id, List<String> addresses) {
    var out = new ByteArrayOutputStream();
    out.writeBytes(header(id, 0, addresses.size()));
    for (String address : addresses) {
      out.writeBytes(DnsQuery.encodeName("ent.ept.sn"));
      write(out, 1); // type A
      write(out, 1); // classe IN
      write(out, 0); // durée de vie (poids fort)
      write(out, 60); // durée de vie (poids faible)
      write(out, 4); // longueur
      for (String part : address.split("\\.")) {
        out.write(Integer.parseInt(part));
      }
    }
    return out.toByteArray();
  }

  private static void write(ByteArrayOutputStream out, int value) {
    out.write((value >> 8) & 0xFF);
    out.write(value & 0xFF);
  }

  /** Serveur de noms de test : lit l'identifiant de la question et répond ce qu'on lui dit. */
  private static final class FakeNameServer implements AutoCloseable {
    private final DatagramSocket socket;
    private final Thread thread;

    FakeNameServer(Function<Integer, byte[]> reply) throws Exception {
      socket = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
      thread =
          new Thread(
              () -> {
                byte[] buffer = new byte[DnsQuery.MAX_MESSAGE];
                while (!socket.isClosed()) {
                  try {
                    var request = new DatagramPacket(buffer, buffer.length);
                    socket.receive(request);
                    int id = ((buffer[0] & 0xFF) << 8) | (buffer[1] & 0xFF);
                    byte[] response = reply.apply(id);
                    if (response != null) {
                      socket.send(
                          new DatagramPacket(
                              response, response.length, request.getAddress(), request.getPort()));
                    }
                  } catch (Exception e) {
                    return;
                  }
                }
              },
              "faux-serveur-dns");
      thread.setDaemon(true);
      thread.start();
    }

    int port() {
      return socket.getLocalPort();
    }

    @Override
    public void close() {
      socket.close();
      thread.interrupt();
    }
  }
}

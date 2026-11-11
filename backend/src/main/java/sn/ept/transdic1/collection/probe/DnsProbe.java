package sn.ept.transdic1.collection.probe;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.security.SecureRandom;
import org.springframework.stereotype.Component;
import sn.ept.transdic1.inventory.domain.ServiceType;

/**
 * Sonde DNS : demande au serveur supervisé de résoudre un nom et mesure le temps de réponse. Un
 * serveur de noms peut répondre au ping et rester incapable de résoudre : c'est cette panne-là que
 * la sonde voit.
 *
 * <p>La cible est l'adresse de l'équipement (le serveur de noms), déjà validée par la garde ; le
 * nom à résoudre est une donnée de la question, jamais une adresse vers laquelle on se connecte. Un
 * texte attendu, s'il est renseigné, doit se trouver parmi les adresses rendues.
 */
@Component
public class DnsProbe implements Probe {
  private static final SecureRandom RANDOM = new SecureRandom();

  @Override
  public ServiceType type() {
    return ServiceType.DNS;
  }

  @Override
  public ProbeResult run(ProbeRequest request) {
    String name = request.path();
    if (name == null || name.isBlank()) {
      return ProbeResult.down("Nom à résoudre manquant");
    }
    int port = request.port() == null ? 53 : request.port();
    byte[] question;
    int id = RANDOM.nextInt(1 << 16);
    try {
      question = DnsQuery.question(name, id);
    } catch (IllegalArgumentException e) {
      return ProbeResult.down("Nom à résoudre invalide");
    }
    long start = System.nanoTime();
    try (DatagramSocket socket = new DatagramSocket()) {
      socket.setSoTimeout((int) Math.min(Integer.MAX_VALUE, request.timeout().toMillis()));
      socket.send(
          new DatagramPacket(
              question, question.length, new InetSocketAddress(request.address(), port)));
      byte[] buffer = new byte[DnsQuery.MAX_MESSAGE];
      var response = new DatagramPacket(buffer, buffer.length);
      socket.receive(response);
      int latency = (int) ((System.nanoTime() - start) / 1_000_000L);
      DnsQuery.Answer answer = DnsQuery.read(buffer, response.getLength(), id);
      String failure = DnsQuery.responseCodeMessage(answer.responseCode());
      if (failure != null) {
        return ProbeResult.down(failure);
      }
      if (answer.addresses().isEmpty()) {
        return ProbeResult.down("Le serveur de noms n'a rendu aucune adresse");
      }
      String expected = request.expectedText();
      if (expected != null
          && !expected.isBlank()
          && !answer.addresses().contains(expected.trim())) {
        return ProbeResult.down("Le nom ne résout pas vers l'adresse attendue");
      }
      return ProbeResult.up(Math.max(1, latency));
    } catch (SocketTimeoutException e) {
      return ProbeResult.down("Délai dépassé");
    } catch (IllegalArgumentException e) {
      return ProbeResult.down("Réponse du serveur de noms illisible");
    } catch (IOException | RuntimeException e) {
      return ProbeResult.down("Interrogation du serveur de noms impossible");
    }
  }
}

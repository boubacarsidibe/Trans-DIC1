package sn.ept.transdic1.collection.probe;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import org.springframework.stereotype.Component;
import sn.ept.transdic1.inventory.domain.ServiceType;

/** Sonde TCP : ouvre une connexion vers l'adresse validée et mesure le temps d'établissement. */
@Component
public class TcpProbe implements Probe {
  @Override
  public ServiceType type() {
    return ServiceType.TCP_PORT;
  }

  @Override
  public ProbeResult run(ProbeRequest request) {
    if (request.port() == null) {
      return ProbeResult.down("Port manquant");
    }
    int timeoutMs = (int) Math.min(Integer.MAX_VALUE, request.timeout().toMillis());
    long start = System.nanoTime();
    try (Socket socket = new Socket()) {
      // Adresse déjà résolue : aucune nouvelle résolution de nom ici.
      socket.connect(new InetSocketAddress(request.address(), request.port()), timeoutMs);
      return ProbeResult.up((int) ((System.nanoTime() - start) / 1_000_000L));
    } catch (SocketTimeoutException e) {
      return ProbeResult.down("Délai dépassé");
    } catch (NoRouteToHostException e) {
      return ProbeResult.down("Hôte injoignable");
    } catch (ConnectException e) {
      String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
      return ProbeResult.down(
          message.contains("timed out") ? "Délai dépassé" : "Connexion refusée");
    } catch (IOException | RuntimeException e) {
      return ProbeResult.down("Connexion impossible");
    }
  }
}

package sn.ept.transdic1.collection.probe;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.util.Locale;
import java.util.concurrent.ScheduledFuture;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.springframework.stereotype.Component;
import sn.ept.transdic1.inventory.domain.ServiceType;

/**
 * Sonde de certificat TLS : établit une poignée de main vers l'adresse déjà validée (aucune
 * résolution de nom), lit le certificat présenté et renvoie le nombre de jours avant expiration.
 *
 * <p>Le résultat reste {@code up} même si le certificat est expiré (jours négatifs) : c'est la
 * règle d'alerte qui décide. Un seul délai total couvre connexion + poignée de main.
 */
@Component
public class TlsCertProbe implements Probe {
  private static final int DEFAULT_PORT = 443;

  private final SSLContext sslContext = newReadOnlyContext();

  @Override
  public ServiceType type() {
    return ServiceType.TLS_CERT;
  }

  @Override
  public ProbeResult run(ProbeRequest request) {
    int port = request.port() == null ? DEFAULT_PORT : request.port();
    if (port < 1 || port > 65535) {
      return ProbeResult.down("Port invalide");
    }
    long timeoutNanos = request.timeout().toNanos();
    long start = System.nanoTime();
    long deadline = start + timeoutNanos;

    Socket plain = new Socket();
    Socket socket = plain;
    ScheduledFuture<?> guard = HttpProbe.closeAtDeadline(plain, timeoutNanos);
    boolean connected = false;
    try {
      // Adresse déjà résolue et validée : connexion uniquement à elle.
      plain.connect(
          new InetSocketAddress(request.address(), port), HttpProbe.remainingMillis(deadline));
      connected = true;
      String peerHost = request.host() == null ? "" : HttpProbe.stripBrackets(request.host());
      SSLSocket ssl =
          (SSLSocket) sslContext.getSocketFactory().createSocket(plain, peerHost, port, true);
      socket = ssl;
      SSLParameters parameters = ssl.getSSLParameters();
      if (!peerHost.isEmpty()) {
        HttpProbe.applySni(parameters, peerHost);
      }
      ssl.setSSLParameters(parameters);
      ssl.setSoTimeout(HttpProbe.remainingMillis(deadline));
      ssl.startHandshake();
      if (!(ssl.getSession().getPeerCertificates()[0] instanceof X509Certificate certificate)) {
        return ProbeResult.down("Certificat illisible");
      }
      int latencyMs = (int) ((System.nanoTime() - start) / 1_000_000L);
      return ProbeResult.up(latencyMs, HttpProbe.daysLeft(certificate));
    } catch (IOException | RuntimeException e) {
      return ProbeResult.down(classify(e, deadline, connected));
    } finally {
      HttpProbe.closeQuietly(socket);
      HttpProbe.closeQuietly(plain);
      guard.cancel(false);
    }
  }

  private static String classify(Exception e, long deadline, boolean connected) {
    if (e instanceof SocketTimeoutException || System.nanoTime() >= deadline) {
      return "Délai dépassé";
    }
    if (connected) {
      return "Poignée de main TLS impossible";
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

  /**
   * Contexte TLS qui accepte volontairement toute chaîne de certificats.
   *
   * <p>Cette confiance totale est voulue : la sonde ne cherche qu'à lire la date d'expiration du
   * certificat serveur (y compris expiré ou auto-signé) et n'envoie ni ne reçoit aucune donnée
   * applicative. Ce contexte reste privé à cette classe et ne doit jamais servir à une connexion
   * qui transmet des données.
   */
  private static SSLContext newReadOnlyContext() {
    TrustManager acceptAll =
        new X509TrustManager() {
          @Override
          public void checkClientTrusted(X509Certificate[] chain, String authType) {
            // jamais utilisé côté client
          }

          @Override
          public void checkServerTrusted(X509Certificate[] chain, String authType) {
            // acceptation volontaire : on ne lit que la date d'expiration
          }

          @Override
          public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
          }
        };
    try {
      SSLContext context = SSLContext.getInstance("TLS");
      context.init(null, new TrustManager[] {acceptAll}, null);
      return context;
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Contexte TLS indisponible", e);
    }
  }
}

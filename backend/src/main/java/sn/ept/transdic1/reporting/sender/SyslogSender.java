package sn.ept.transdic1.reporting.sender;

import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import sn.ept.transdic1.common.secret.SecretResolver;
import sn.ept.transdic1.reporting.domain.NotificationChannel;
import sn.ept.transdic1.reporting.port.NotificationSender;

/**
 * Envoi des alertes à un collecteur syslog — un vrai SIEM, Wazuh ou autre (S8 du plan).
 *
 * <p>L'école n'a pas à choisir entre Trans-DIC1 et un SIEM : les alertes de la plateforme partent
 * vers le collecteur, et les alertes du SIEM reviennent par le récepteur syslog déjà en place (ADR
 * 0021). Chacun fait ce qu'il sait faire.
 *
 * <p>L'adresse du collecteur est une référence de secret, comme pour les autres canaux non
 * courriel, et sa valeur s'écrit {@code udp://hote:514} ou {@code tcp://hote:514}. Ce n'est pas un
 * secret à proprement parler, mais cela garde l'adresse d'un équipement d'infrastructure hors de la
 * base et hors des écrans, et évite d'inventer une seconde façon de désigner une destination.
 *
 * <p>En UDP, rien ne garantit l'arrivée : le protocole ne le permet pas, et c'est le fonctionnement
 * habituel du syslog. En TCP, l'échec de connexion remonte et la notification est réessayée comme
 * les autres.
 */
@Component
public class SyslogSender implements NotificationSender {
  /** Port syslog par défaut, si l'adresse n'en indique pas. */
  static final int DEFAULT_PORT = 514;

  private static final int CONNECT_TIMEOUT_MS = 5_000;

  private final SecretResolver secrets;
  private final Clock clock;
  private final int facility;
  private final String hostname;

  public SyslogSender(
      SecretResolver secrets,
      Clock clock,
      @Value("${notification.syslog.facility:20}") int facility,
      @Value("${notification.syslog.hostname:}") String hostname) {
    this.secrets = secrets;
    this.clock = clock;
    this.facility = facility;
    this.hostname = hostname;
  }

  @Override
  public NotificationChannel channel() {
    return NotificationChannel.SYSLOG;
  }

  @Override
  public void send(String destination, String subject, String body) throws Exception {
    send(destination, subject, body, Context.unknown());
  }

  @Override
  public void send(String destination, String subject, String body, Context context)
      throws Exception {
    String message =
        Syslog.format(
            facility,
            context.severity(),
            clock.instant(),
            origin(),
            context.messageId(),
            subject,
            body);
    URI target = target(destination);
    byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
    if ("tcp".equals(target.getScheme())) {
      sendOverTcp(target, bytes);
    } else {
      sendOverUdp(target, bytes);
    }
  }

  /** Nom annoncé comme origine : celui de la configuration, sinon celui de la machine. */
  private String origin() {
    if (!hostname.isBlank()) {
      return hostname;
    }
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (Exception e) {
      // Un hôte qui ne sait pas son nom n'est pas une raison de perdre l'alerte.
      return "-";
    }
  }

  /** Adresse du collecteur, résolue depuis la référence de secret puis vérifiée. */
  static URI parse(String value) {
    String text = value == null ? "" : value.trim();
    if (!text.contains("://")) {
      // Tolérance : « hote:514 » vaut « udp://hote:514 ».
      text = "udp://" + text;
    }
    URI uri;
    try {
      uri = URI.create(text);
    } catch (IllegalArgumentException e) {
      // Faute de frappe dans le secret : le message doit dire quoi écrire, pas citer la pile.
      throw new IllegalStateException(
          "Adresse de collecteur syslog illisible : attendue sous la forme udp://hote:514 ou"
              + " tcp://hote:514");
    }
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    if (!"udp".equals(scheme) && !"tcp".equals(scheme)) {
      throw new IllegalStateException(
          "Adresse de collecteur syslog attendue sous la forme udp://hote:514 ou tcp://hote:514");
    }
    if (uri.getHost() == null || uri.getHost().isBlank()) {
      throw new IllegalStateException("Adresse de collecteur syslog sans nom d'hôte");
    }
    return uri;
  }

  private URI target(String destination) {
    return parse(secrets.resolve(destination));
  }

  private void sendOverUdp(URI target, byte[] bytes) throws Exception {
    try (var socket = new DatagramSocket()) {
      var address = InetAddress.getByName(target.getHost());
      socket.send(new DatagramPacket(bytes, bytes.length, address, port(target)));
    }
  }

  private void sendOverTcp(URI target, byte[] bytes) throws Exception {
    try (var socket = new Socket()) {
      socket.connect(new InetSocketAddress(target.getHost(), port(target)), CONNECT_TIMEOUT_MS);
      socket.setSoTimeout(CONNECT_TIMEOUT_MS);
      OutputStream out = socket.getOutputStream();
      // Cadrage par retour à la ligne (RFC 6587, « non-transparent framing ») : c'est ce que
      // lisent les collecteurs courants, rsyslog et Wazuh compris.
      out.write(bytes);
      out.write('\n');
      out.flush();
    }
  }

  private static int port(URI target) {
    return target.getPort() > 0 ? target.getPort() : DEFAULT_PORT;
  }
}

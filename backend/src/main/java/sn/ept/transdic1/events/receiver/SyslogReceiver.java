package sn.ept.transdic1.events.receiver;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import sn.ept.transdic1.events.domain.EventSeverity;
import sn.ept.transdic1.events.domain.EventSource;
import sn.ept.transdic1.events.domain.EventText;
import sn.ept.transdic1.events.service.EventMetrics;

/**
 * Récepteur syslog UDP (RFC 3164 et RFC 5424), désactivé par défaut ({@code
 * events.syslog.enabled}). Port 1514 par défaut : 514 demande des privilèges. Le thread du socket
 * ne fait que lire, analyser (8 Kio au plus) et déposer dans la file : il ne touche jamais la base.
 */
@Component
@ConditionalOnProperty(name = "events.syslog.enabled", havingValue = "true")
public class SyslogReceiver implements SmartLifecycle {
  private static final Logger LOG = LoggerFactory.getLogger(SyslogReceiver.class);

  private final EventIntake intake;
  private final EventMetrics metrics;
  private final Clock clock;
  private final String bindAddress;
  private final int port;
  private volatile DatagramSocket socket;
  private volatile Thread thread;
  private volatile boolean running;

  public SyslogReceiver(
      EventIntake intake,
      EventMetrics metrics,
      Clock clock,
      @Value("${events.syslog.bind-address:0.0.0.0}") String bindAddress,
      @Value("${events.syslog.port:1514}") int port) {
    this.intake = intake;
    this.metrics = metrics;
    this.clock = clock;
    this.bindAddress = bindAddress;
    this.port = port;
  }

  @Override
  public synchronized void start() {
    if (running) {
      return;
    }
    try {
      socket = new DatagramSocket(new InetSocketAddress(InetAddress.getByName(bindAddress), port));
    } catch (IOException e) {
      throw new UncheckedIOException("Port syslog " + port + " indisponible", e);
    }
    intake.start();
    running = true;
    thread = new Thread(this::listen, "syslog-udp");
    thread.setDaemon(true);
    thread.start();
    LOG.info("Récepteur syslog à l'écoute sur {}:{} (UDP)", bindAddress, socket.getLocalPort());
  }

  private void listen() {
    byte[] buffer = new byte[SyslogParser.MAX_BYTES + 1];
    while (running) {
      var packet = new DatagramPacket(buffer, buffer.length);
      try {
        socket.receive(packet);
      } catch (SocketException e) {
        if (!running) {
          return; // socket fermé par stop()
        }
        LOG.warn("Erreur du socket syslog : {}", e.getClass().getSimpleName());
        continue;
      } catch (IOException e) {
        LOG.warn("Lecture syslog impossible : {}", e.getClass().getSimpleName());
        continue;
      }
      try {
        handle(packet);
      } catch (RuntimeException e) {
        LOG.warn("Datagramme syslog ignoré : {}", e.getClass().getSimpleName());
      }
    }
  }

  private void handle(DatagramPacket packet) {
    metrics.received(EventSource.SYSLOG);
    SyslogMessage message = SyslogParser.parse(packet.getData(), packet.getLength());
    if (message.truncated()) {
      metrics.truncated();
    }
    String text = message.message();
    String title = message.appName() == null ? text : message.appName() + " : " + text;
    intake.submit(
        new ReceivedEvent(
            EventSource.SYSLOG,
            packet.getAddress().getHostAddress(),
            EventSeverity.fromCode(message.severity()),
            EventText.clean(title.isBlank() ? "(message vide)" : title, EventText.MAX_TITLE),
            EventText.cleanOrNull(text, EventText.MAX_DETAILS),
            null,
            message.hostname(),
            message.appName(),
            message.facility(),
            clock.instant()));
  }

  @Override
  public synchronized void stop() {
    if (!running) {
      return;
    }
    running = false;
    socket.close();
    try {
      thread.join(2000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    LOG.info("Récepteur syslog arrêté");
  }

  @PreDestroy
  void destroy() {
    stop();
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  /** Port réellement ouvert (utile avec le port 0 des tests). */
  public int port() {
    return socket == null ? -1 : socket.getLocalPort();
  }
}

package sn.ept.transdic1.flow.receiver;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import sn.ept.transdic1.flow.service.FlowAccumulator;
import sn.ept.transdic1.flow.service.FlowObservations;
import sn.ept.transdic1.flow.service.FlowSources;

/**
 * Récepteur NetFlow UDP (ADR 0032), <b>désactivé par défaut</b> ({@code flow.enabled}). Port 2055
 * par défaut, celui qu'emploient la plupart des exportateurs.
 *
 * <p>Le thread du socket ne fait que lire, décoder et additionner en mémoire : il ne touche jamais
 * la base. L'écriture est l'affaire du vidage d'une minute sur l'autre.
 *
 * <p>Garde-fous, parce que ce port reçoit ce que le réseau veut bien lui envoyer :
 *
 * <ul>
 *   <li>l'adresse d'origine doit être l'adresse de gestion d'un équipement supervisé, sinon le
 *       paquet est jeté sans rien conserver (l'UDP se falsifie) ;
 *   <li>un plafond de paquets par seconde et par source ;
 *   <li>un datagramme plus long que {@code flow.max-packet-bytes} est tronqué à la lecture.
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "flow.enabled", havingValue = "true")
public class NetFlowReceiver implements SmartLifecycle {
  private static final Logger LOG = LoggerFactory.getLogger(NetFlowReceiver.class);

  private final FlowSources sources;
  private final FlowAccumulator accumulator;
  private final FlowObservations observations;
  private final FlowTemplates templates;
  private final FlowRateLimiter limiter;
  private final String bindAddress;
  private final int port;
  private final int maxPacketBytes;
  private volatile DatagramSocket socket;
  private volatile Thread thread;
  private volatile boolean running;
  private volatile long rejected;

  public NetFlowReceiver(
      FlowSources sources,
      FlowAccumulator accumulator,
      FlowObservations observations,
      Clock clock,
      @Value("${flow.bind-address:0.0.0.0}") String bindAddress,
      @Value("${flow.port:2055}") int port,
      @Value("${flow.max-packet-bytes:8192}") int maxPacketBytes,
      @Value("${flow.packets-per-second:200}") int packetsPerSecond,
      @Value("${flow.template-ttl-minutes:60}") long templateTtlMinutes) {
    this.sources = sources;
    this.accumulator = accumulator;
    this.observations = observations;
    this.templates = new FlowTemplates(clock, Duration.ofMinutes(Math.max(1, templateTtlMinutes)));
    this.limiter = new FlowRateLimiter(clock, Math.max(10, packetsPerSecond));
    this.bindAddress = bindAddress;
    this.port = port;
    this.maxPacketBytes = Math.max(1_500, maxPacketBytes);
  }

  @Override
  public synchronized void start() {
    if (running) {
      return;
    }
    try {
      socket = new DatagramSocket(new InetSocketAddress(InetAddress.getByName(bindAddress), port));
    } catch (IOException e) {
      throw new UncheckedIOException("Port NetFlow " + port + " indisponible", e);
    }
    running = true;
    thread = new Thread(this::listen, "netflow-udp");
    thread.setDaemon(true);
    thread.start();
    LOG.info("Récepteur NetFlow à l'écoute sur {}:{} (UDP)", bindAddress, socket.getLocalPort());
  }

  private void listen() {
    byte[] buffer = new byte[maxPacketBytes];
    while (running) {
      var packet = new DatagramPacket(buffer, buffer.length);
      try {
        socket.receive(packet);
      } catch (SocketException e) {
        if (!running) {
          return; // socket fermé par stop()
        }
        LOG.warn("Erreur du socket NetFlow : {}", e.getClass().getSimpleName());
        continue;
      } catch (IOException e) {
        LOG.warn("Lecture NetFlow impossible : {}", e.getClass().getSimpleName());
        continue;
      }
      try {
        handle(packet);
      } catch (RuntimeException e) {
        LOG.warn("Datagramme NetFlow ignoré : {}", e.getClass().getSimpleName());
      }
    }
  }

  private void handle(DatagramPacket packet) {
    String exporter = packet.getAddress().getHostAddress();
    UUID equipmentId = sources.equipmentAt(exporter);
    if (equipmentId == null || !limiter.accept(exporter)) {
      // Source inconnue ou trop bavarde : rien n'est décodé, rien n'est conservé.
      rejected++;
      return;
    }
    List<FlowRecord> flows =
        NetFlowDecoder.decode(packet.getData(), packet.getLength(), exporter, templates);
    for (FlowRecord flow : flows) {
      accumulator.add(equipmentId, flow);
      observations.add(equipmentId, flow);
    }
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
    templates.clear();
    LOG.info("Récepteur NetFlow arrêté");
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

  /** Paquets rejetés depuis le démarrage : source inconnue ou débit excessif. */
  public long rejected() {
    return rejected;
  }

  /** Gabarits retenus en ce moment. */
  public int templateCount() {
    return templates.size();
  }
}

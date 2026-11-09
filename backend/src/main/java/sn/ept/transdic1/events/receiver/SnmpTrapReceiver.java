package sn.ept.transdic1.events.receiver;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snmp4j.CommandResponder;
import org.snmp4j.CommandResponderEvent;
import org.snmp4j.MessageDispatcherImpl;
import org.snmp4j.MessageException;
import org.snmp4j.PDU;
import org.snmp4j.Snmp;
import org.snmp4j.mp.MPv1;
import org.snmp4j.mp.MPv2c;
import org.snmp4j.mp.MPv3;
import org.snmp4j.mp.StatusInformation;
import org.snmp4j.security.AuthSHA;
import org.snmp4j.security.PrivAES128;
import org.snmp4j.security.SecurityLevel;
import org.snmp4j.security.SecurityProtocols;
import org.snmp4j.security.USM;
import org.snmp4j.security.UsmUser;
import org.snmp4j.smi.IpAddress;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.UdpAddress;
import org.snmp4j.transport.DefaultUdpTransportMapping;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import sn.ept.transdic1.common.secret.SecretResolver;
import sn.ept.transdic1.events.domain.EventSource;
import sn.ept.transdic1.events.service.EventMetrics;

/**
 * Récepteur de traps et d'informs SNMP (v1, v2c, v3) avec SNMP4J, désactivé par défaut ({@code
 * events.snmp-trap.enabled}). Port 1162 par défaut : 162 demande des privilèges.
 *
 * <ul>
 *   <li>v1/v2c : la communauté doit valoir le secret de {@code events.snmp-trap.community} (forme
 *       {@code env:NOM}, comparaison à temps constant). Sans référence, toute communauté est
 *       acceptée et un avertissement est journalisé au démarrage.
 *   <li>v3 : un seul utilisateur configuré ({@code events.snmp-trap.v3.*}, SHA et AES-128 comme la
 *       collecte), au moins authentifié. Sans utilisateur, le modèle v3 n'est pas chargé et les
 *       messages v3 sont ignorés.
 *   <li>Le moteur v3 a son propre USM : il ne partage rien avec la collecte SNMP.
 * </ul>
 *
 * Aucun secret n'est journalisé. Le thread du socket décode et dépose dans la file, sans base.
 */
@Component
@ConditionalOnProperty(name = "events.snmp-trap.enabled", havingValue = "true")
public class SnmpTrapReceiver implements SmartLifecycle, CommandResponder {
  private static final Logger LOG = LoggerFactory.getLogger(SnmpTrapReceiver.class);

  private final EventIntake intake;
  private final EventMetrics metrics;
  private final SecretResolver secrets;
  private final Clock clock;
  private final String bindAddress;
  private final int port;
  private final String communityReference;
  private final String v3User;
  private final String v3AuthReference;
  private final String v3PrivReference;

  private volatile Snmp snmp;
  private volatile DefaultUdpTransportMapping transport;
  private volatile byte[] community;
  private volatile boolean running;

  public SnmpTrapReceiver(
      EventIntake intake,
      EventMetrics metrics,
      SecretResolver secrets,
      Clock clock,
      @Value("${events.snmp-trap.bind-address:0.0.0.0}") String bindAddress,
      @Value("${events.snmp-trap.port:1162}") int port,
      @Value("${events.snmp-trap.community:}") String communityReference,
      @Value("${events.snmp-trap.v3.username:}") String v3User,
      @Value("${events.snmp-trap.v3.auth-reference:}") String v3AuthReference,
      @Value("${events.snmp-trap.v3.priv-reference:}") String v3PrivReference) {
    this.intake = intake;
    this.metrics = metrics;
    this.secrets = secrets;
    this.clock = clock;
    this.bindAddress = bindAddress;
    this.port = port;
    this.communityReference = blankToNull(communityReference);
    this.v3User = blankToNull(v3User);
    this.v3AuthReference = blankToNull(v3AuthReference);
    this.v3PrivReference = blankToNull(v3PrivReference);
  }

  @Override
  public synchronized void start() {
    if (running) {
      return;
    }
    if (communityReference == null) {
      community = null;
      LOG.warn(
          "Traps SNMP v1/v2c NON authentifiés : events.snmp-trap.community n'est pas défini,"
              + " toute communauté est acceptée (seule l'adresse source est contrôlée)");
    } else {
      community = secrets.resolve(communityReference).getBytes(StandardCharsets.UTF_8);
    }
    try {
      var dispatcher = new MessageDispatcherImpl();
      dispatcher.addMessageProcessingModel(new MPv1());
      dispatcher.addMessageProcessingModel(new MPv2c());
      if (v3User != null) {
        dispatcher.addMessageProcessingModel(new MPv3(v3Usm()));
      }
      transport =
          new DefaultUdpTransportMapping(
              new UdpAddress(InetAddress.getByName(bindAddress), port), true);
      snmp = new Snmp(dispatcher, transport);
      snmp.addCommandResponder(this);
      snmp.listen();
    } catch (IOException e) {
      throw new UncheckedIOException("Port de réception des traps " + port + " indisponible", e);
    }
    intake.start();
    running = true;
    LOG.info(
        "Récepteur de traps SNMP à l'écoute sur {}:{} (UDP, v1/v2c{})",
        bindAddress,
        port(),
        v3User == null ? "" : ", v3");
  }

  /** USM propre au récepteur, avec le seul utilisateur v3 configuré. */
  private USM v3Usm() {
    if (!SecretResolver.isSupported(v3AuthReference)) {
      throw new IllegalStateException(
          "events.snmp-trap.v3.auth-reference doit être une référence de la forme env:NOM");
    }
    SecurityProtocols protocols = SecurityProtocols.getInstance();
    protocols.addAuthenticationProtocol(new AuthSHA());
    protocols.addPrivacyProtocol(new PrivAES128());
    var usm = new USM(protocols, new OctetString(MPv3.createLocalEngineID()), 0);
    String priv = v3PrivReference == null ? null : secrets.resolve(v3PrivReference);
    usm.addUser(
        new OctetString(v3User),
        new UsmUser(
            new OctetString(v3User),
            AuthSHA.ID,
            new OctetString(secrets.resolve(v3AuthReference)),
            priv == null ? null : PrivAES128.ID,
            priv == null ? null : new OctetString(priv)));
    return usm;
  }

  @Override
  public <A extends org.snmp4j.smi.Address> void processPdu(CommandResponderEvent<A> event) {
    PDU pdu = event.getPDU();
    if (pdu == null
        || (pdu.getType() != PDU.TRAP
            && pdu.getType() != PDU.V1TRAP
            && pdu.getType() != PDU.INFORM)) {
      return;
    }
    event.setProcessed(true);
    metrics.received(EventSource.SNMP_TRAP);
    if (!(event.getPeerAddress() instanceof IpAddress peer)) {
      return;
    }
    if (!authenticated(event)) {
      return;
    }
    boolean accepted =
        intake.submit(
            TrapDecoder.decode(pdu, peer.getInetAddress().getHostAddress(), clock.instant()));
    // Accusé seulement pour un inform accepté (source connue, débit et file respectés) : jamais de
    // réponse vers une adresse usurpée (pas de réflecteur), et un inform rejeté sera réémis.
    if (accepted && pdu.getType() == PDU.INFORM) {
      acknowledge(event, pdu);
    }
  }

  private boolean authenticated(CommandResponderEvent<?> event) {
    if (event.getMessageProcessingModel() == org.snmp4j.mp.MessageProcessingModel.MPv3) {
      // L'USM a déjà vérifié l'utilisateur et la signature ; on exige au moins authNoPriv.
      boolean ok =
          v3User != null
              && event.getSecurityLevel() >= SecurityLevel.AUTH_NOPRIV
              && v3User.equals(new String(event.getSecurityName(), StandardCharsets.UTF_8));
      if (!ok) {
        metrics.dropped("bad_user");
      }
      return ok;
    }
    byte[] expected = community;
    if (expected != null && !MessageDigest.isEqual(expected, event.getSecurityName())) {
      metrics.dropped("bad_community");
      return false;
    }
    return true;
  }

  /** Accusé de réception d'un inform : la même PDU renvoyée en réponse. */
  private void acknowledge(CommandResponderEvent<?> event, PDU pdu) {
    PDU response = (PDU) pdu.clone();
    response.setType(PDU.RESPONSE);
    response.setErrorStatus(PDU.noError);
    response.setErrorIndex(0);
    try {
      event
          .getMessageDispatcher()
          .returnResponsePdu(
              event.getMessageProcessingModel(),
              event.getSecurityModel(),
              event.getSecurityName(),
              event.getSecurityLevel(),
              response,
              event.getMaxSizeResponsePDU(),
              event.getStateReference(),
              new StatusInformation());
    } catch (MessageException e) {
      LOG.debug("Accusé d'inform non envoyé : {}", e.getClass().getSimpleName());
    }
  }

  @Override
  public synchronized void stop() {
    if (!running) {
      return;
    }
    running = false;
    try {
      snmp.close();
    } catch (IOException e) {
      LOG.debug("Fermeture du récepteur de traps : {}", e.getClass().getSimpleName());
    }
    LOG.info("Récepteur de traps SNMP arrêté");
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
    return transport == null ? -1 : transport.getListenAddress().getPort();
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}

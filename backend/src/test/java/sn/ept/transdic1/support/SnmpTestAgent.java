package sn.ept.transdic1.support;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.snmp4j.CommandResponder;
import org.snmp4j.CommandResponderEvent;
import org.snmp4j.MessageDispatcherImpl;
import org.snmp4j.PDU;
import org.snmp4j.ScopedPDU;
import org.snmp4j.Snmp;
import org.snmp4j.mp.MPv2c;
import org.snmp4j.mp.MPv3;
import org.snmp4j.mp.StatusInformation;
import org.snmp4j.security.AuthSHA;
import org.snmp4j.security.PrivAES128;
import org.snmp4j.security.SecurityProtocols;
import org.snmp4j.security.USM;
import org.snmp4j.security.UsmUser;
import org.snmp4j.smi.Address;
import org.snmp4j.smi.Null;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.UdpAddress;
import org.snmp4j.smi.Variable;
import org.snmp4j.smi.VariableBinding;
import org.snmp4j.transport.DefaultUdpTransportMapping;

/**
 * Faux équipement SNMP pour les tests : répond aux GET sur une valeur fixe par OID, en v2c (si la
 * communauté est la bonne, sinon silence comme un vrai équipement) et en v3 (utilisateur connu).
 */
public final class SnmpTestAgent implements AutoCloseable {
  private final Snmp snmp;
  private final DefaultUdpTransportMapping transport;
  private final String community;
  private final Map<String, Variable> values = new ConcurrentHashMap<>();

  public SnmpTestAgent(String community, UsmUser v3User) throws IOException {
    this.community = community;
    transport = new DefaultUdpTransportMapping(new UdpAddress("127.0.0.1/0"));
    SecurityProtocols.getInstance().addDefaultProtocols();
    SecurityProtocols.getInstance().addAuthenticationProtocol(new AuthSHA());
    SecurityProtocols.getInstance().addPrivacyProtocol(new PrivAES128());
    var usm =
        new USM(SecurityProtocols.getInstance(), new OctetString(MPv3.createLocalEngineID()), 0);
    // Moteur de sécurité propre à ce faux équipement : il ne partage pas les utilisateurs du
    // client.
    var dispatcher = new MessageDispatcherImpl();
    dispatcher.addMessageProcessingModel(new MPv2c());
    dispatcher.addMessageProcessingModel(new MPv3(usm));
    snmp = new Snmp(dispatcher, transport);
    if (v3User != null) {
      usm.addUser(v3User);
    }
    snmp.addCommandResponder(
        new CommandResponder() {
          @Override
          public <A extends Address> void processPdu(CommandResponderEvent<A> event) {
            handle(event);
          }
        });
    snmp.listen();
  }

  public int port() {
    return ((UdpAddress) transport.getListenAddress()).getPort();
  }

  public void set(String oid, Variable value) {
    values.put(oid, value);
  }

  public void clear() {
    values.clear();
  }

  private <A extends Address> void handle(CommandResponderEvent<A> event) {
    PDU request = event.getPDU();
    System.out.println(
        "AGENT-REQ mp="
            + event.getMessageProcessingModel()
            + " sec="
            + event.getSecurityName()
            + " pdu="
            + request);
    if (request == null || request.getType() != PDU.GET) {
      return;
    }
    if (event.getMessageProcessingModel() == MPv2c.ID
        && !community.equals(new String(event.getSecurityName()))) {
      event.setProcessed(true);
      return; // communauté inconnue : un vrai équipement reste muet
    }
    PDU response;
    if (request instanceof ScopedPDU scoped) {
      var reply = new ScopedPDU();
      reply.setContextEngineID(scoped.getContextEngineID());
      reply.setContextName(scoped.getContextName());
      response = reply;
    } else {
      response = new PDU();
    }
    response.setType(PDU.RESPONSE);
    response.setRequestID(request.getRequestID());
    for (VariableBinding binding : request.getVariableBindings()) {
      Variable value = values.get(binding.getOid().toDottedString());
      response.add(
          new VariableBinding(binding.getOid(), value != null ? value : Null.noSuchObject));
    }
    try {
      snmp.getMessageDispatcher()
          .returnResponsePdu(
              event.getMessageProcessingModel(),
              event.getSecurityModel(),
              event.getSecurityName(),
              event.getSecurityLevel(),
              response,
              event.getMaxSizeResponsePDU(),
              event.getStateReference(),
              new StatusInformation());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    event.setProcessed(true);
  }

  @Override
  public void close() throws IOException {
    snmp.close();
  }
}

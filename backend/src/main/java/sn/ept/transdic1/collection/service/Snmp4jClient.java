package sn.ept.transdic1.collection.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import org.snmp4j.AbstractTarget;
import org.snmp4j.CommunityTarget;
import org.snmp4j.PDU;
import org.snmp4j.ScopedPDU;
import org.snmp4j.Snmp;
import org.snmp4j.UserTarget;
import org.snmp4j.mp.MPv3;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.security.AuthSHA;
import org.snmp4j.security.PrivAES128;
import org.snmp4j.security.SecurityLevel;
import org.snmp4j.security.SecurityModel;
import org.snmp4j.security.SecurityModels;
import org.snmp4j.security.SecurityProtocols;
import org.snmp4j.security.USM;
import org.snmp4j.security.UsmUser;
import org.snmp4j.smi.Counter64;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.UdpAddress;
import org.snmp4j.smi.Variable;
import org.snmp4j.smi.VariableBinding;
import org.snmp4j.transport.DefaultUdpTransportMapping;
import org.springframework.stereotype.Component;
import sn.ept.transdic1.inventory.domain.SnmpVersion;

/**
 * Lecture SNMP v2c et v3 avec SNMP4J. Chaque lecture ouvre sa propre session (et, en v3, son propre
 * moteur de sécurité) : des équipements aux identifiants différents ne se mélangent jamais. Les
 * messages d'erreur ne citent ni communauté ni mot de passe.
 */
@Component
class Snmp4jClient implements SnmpClient {

  /**
   * Le moteur de sécurité v3 de SNMP4J est partagé par tout le processus : deux équipements dont
   * l'utilisateur porte le même nom mais pas le même mot de passe se mélangeraient s'ils étaient
   * lus en même temps. Les lectures v3 sont donc sérialisées ; les lectures v2c restent parallèles.
   */
  private static final Object V3_LOCK = new Object();

  @Override
  public List<Reading> get(Target target, List<String> oids) throws SnmpFailure {
    if (target.version() == SnmpVersion.V3) {
      synchronized (V3_LOCK) {
        try {
          return read(target, oids);
        } finally {
          // Les identifiants ne restent pas en mémoire une fois la lecture terminée.
          var usm =
              (USM)
                  SecurityModels.getInstance()
                      .getSecurityModel(new Integer32(SecurityModel.SECURITY_MODEL_USM));
          if (usm != null) {
            usm.removeAllUsers(new OctetString(target.username()));
          }
        }
      }
    }
    return read(target, oids);
  }

  private List<Reading> read(Target target, List<String> oids) throws SnmpFailure {
    try (Snmp snmp = open(target)) {
      var destination = destination(target);
      PDU pdu = target.version() == SnmpVersion.V3 ? new ScopedPDU() : new PDU();
      pdu.setType(PDU.GET);
      for (String oid : oids) {
        pdu.add(new VariableBinding(new OID(oid)));
      }
      var response = snmp.send(pdu, destination);
      if (response == null || response.getResponse() == null) {
        throw new SnmpFailure("Aucune réponse de l'équipement (délai dépassé)");
      }
      PDU answer = response.getResponse();
      if (answer.getErrorStatus() != PDU.noError) {
        throw new SnmpFailure("Erreur SNMP : " + answer.getErrorStatusText());
      }
      var readings = new ArrayList<Reading>();
      for (VariableBinding binding : answer.getVariableBindings()) {
        readings.add(reading(binding));
      }
      return readings;
    } catch (IOException | IllegalArgumentException e) {
      throw new SnmpFailure("Lecture SNMP impossible : " + e.getClass().getSimpleName(), e);
    }
  }

  private Snmp open(Target target) throws IOException {
    var snmp = new Snmp(new DefaultUdpTransportMapping());
    if (target.version() == SnmpVersion.V3) {
      ensureLocalUsm();
      // Une entrée restée en mémoire d'une lecture précédente (même nom, autre mot de passe ou mot
      // de
      // passe changé) serait utilisée à la place des identifiants actuels : on repart de zéro.
      snmp.getUSM().removeAllUsers(new OctetString(target.username()));
      snmp.getUSM()
          .addUser(
              new UsmUser(
                  new OctetString(target.username()),
                  AuthSHA.ID,
                  new OctetString(target.authPassphrase()),
                  target.privPassphrase() == null ? null : PrivAES128.ID,
                  target.privPassphrase() == null
                      ? null
                      : new OctetString(target.privPassphrase())));
    }
    snmp.listen();
    return snmp;
  }

  /** Le moteur de sécurité v3 de SNMP4J est partagé : on l'installe une seule fois. */
  private static synchronized void ensureLocalUsm() {
    // SHA-1 et AES-128 : ce que la plupart des équipements réseau savent faire en SNMPv3.
    SecurityProtocols.getInstance().addDefaultProtocols();
    SecurityProtocols.getInstance().addAuthenticationProtocol(new AuthSHA());
    SecurityProtocols.getInstance().addPrivacyProtocol(new PrivAES128());
    if (SecurityModels.getInstance()
            .getSecurityModel(new Integer32(SecurityModel.SECURITY_MODEL_USM))
        == null) {
      SecurityModels.getInstance()
          .addSecurityModel(
              new USM(
                  SecurityProtocols.getInstance(), new OctetString(MPv3.createLocalEngineID()), 0));
    }
  }

  private AbstractTarget<UdpAddress> destination(Target target) throws IOException {
    var address = new UdpAddress(InetAddress.getByName(target.host()), target.port());
    AbstractTarget<UdpAddress> destination;
    if (target.version() == SnmpVersion.V3) {
      var user = new UserTarget<UdpAddress>();
      user.setSecurityName(new OctetString(target.username()));
      user.setSecurityLevel(
          target.privPassphrase() == null ? SecurityLevel.AUTH_NOPRIV : SecurityLevel.AUTH_PRIV);
      user.setVersion(SnmpConstants.version3);
      destination = user;
    } else {
      var community = new CommunityTarget<UdpAddress>();
      community.setCommunity(new OctetString(target.community()));
      community.setVersion(SnmpConstants.version2c);
      destination = community;
    }
    destination.setAddress(address);
    destination.setTimeout(target.timeout().toMillis());
    destination.setRetries(target.retries());
    return destination;
  }

  static Reading reading(VariableBinding binding) {
    String oid = binding.getOid().toDottedString();
    Variable variable = binding.getVariable();
    if (variable.isException()) {
      return new Reading(oid, null, null);
    }
    if (variable instanceof Counter64 counter) {
      return new Reading(oid, new BigDecimal(java.math.BigInteger.valueOf(counter.toLong())), null);
    }
    if (variable instanceof Integer32
        || variable.getSyntax() == org.snmp4j.smi.SMIConstants.SYNTAX_GAUGE32
        || variable.getSyntax() == org.snmp4j.smi.SMIConstants.SYNTAX_COUNTER32
        || variable.getSyntax() == org.snmp4j.smi.SMIConstants.SYNTAX_TIMETICKS) {
      return new Reading(oid, BigDecimal.valueOf(variable.toLong()), null);
    }
    if (variable instanceof OctetString text) {
      String value = text.toString();
      try {
        return new Reading(oid, new BigDecimal(value.trim()), value);
      } catch (NumberFormatException e) {
        return new Reading(oid, null, value);
      }
    }
    return new Reading(oid, null, variable.toString());
  }
}

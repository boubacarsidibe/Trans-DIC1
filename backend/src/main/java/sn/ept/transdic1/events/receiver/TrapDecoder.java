package sn.ept.transdic1.events.receiver;

import java.time.Instant;
import java.util.Map;
import org.snmp4j.PDU;
import org.snmp4j.PDUv1;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.VariableBinding;
import sn.ept.transdic1.events.domain.EventSeverity;
import sn.ept.transdic1.events.domain.EventSource;
import sn.ept.transdic1.events.domain.EventText;

/**
 * Traduit une notification SNMP (trap v1, trap v2c/v3 ou inform) en événement. L'OID de la
 * notification vient de {@code snmpTrapOID.0} ; un trap v1 est converti selon la RFC 3584. La
 * sévérité vient d'une table fixe des notifications standard (RFC 3418), NOTICE sinon.
 */
public final class TrapDecoder {
  private static final String STANDARD = "1.3.6.1.6.3.1.1.5.";

  private record Known(String title, EventSeverity severity) {}

  private static final Map<String, Known> KNOWN =
      Map.of(
          STANDARD + "1", new Known("coldStart (démarrage à froid)", EventSeverity.NOTICE),
          STANDARD + "2", new Known("warmStart (redémarrage)", EventSeverity.NOTICE),
          STANDARD + "3", new Known("linkDown (interface coupée)", EventSeverity.ERROR),
          STANDARD + "4", new Known("linkUp (interface rétablie)", EventSeverity.NOTICE),
          STANDARD + "5",
              new Known("authenticationFailure (échec d'authentification)", EventSeverity.WARNING));

  private TrapDecoder() {}

  public static ReceivedEvent decode(PDU pdu, String sourceAddress, Instant at) {
    String oid = trapOid(pdu);
    Known known = oid == null ? null : KNOWN.get(oid);
    String title =
        known != null
            ? known.title()
            : oid == null ? "Notification SNMP sans OID" : "Notification " + oid;
    return new ReceivedEvent(
        EventSource.SNMP_TRAP,
        sourceAddress,
        known == null ? EventSeverity.NOTICE : known.severity(),
        EventText.clean(title, EventText.MAX_TITLE),
        details(pdu),
        oid,
        null,
        null,
        null,
        at);
  }

  /** OID de la notification, sans point initial ; nul s'il manque. */
  static String trapOid(PDU pdu) {
    if (pdu instanceof PDUv1 v1) {
      int generic = v1.getGenericTrap();
      if (generic >= 0 && generic < PDUv1.ENTERPRISE_SPECIFIC) {
        return STANDARD + (generic + 1);
      }
      OID enterprise = v1.getEnterprise();
      return enterprise == null ? null : enterprise.toDottedString() + ".0." + v1.getSpecificTrap();
    }
    var value = pdu.getVariable(SnmpConstants.snmpTrapOID);
    if (value instanceof OID oid) {
      return oid.toDottedString();
    }
    return null;
  }

  /**
   * Variables « oid = valeur » séparées par « ; », sans sysUpTime ni snmpTrapOID, 4 Kio au plus.
   */
  private static String details(PDU pdu) {
    StringBuilder out = new StringBuilder();
    for (VariableBinding binding : pdu.getVariableBindings()) {
      OID oid = binding.getOid();
      if (SnmpConstants.sysUpTime.equals(oid) || SnmpConstants.snmpTrapOID.equals(oid)) {
        continue;
      }
      if (!out.isEmpty()) {
        out.append(" ; ");
      }
      out.append(oid.toDottedString()).append(" = ").append(binding.getVariable().toString());
      if (out.length() > EventText.MAX_DETAILS) {
        break;
      }
    }
    return EventText.clean(out.toString(), EventText.MAX_DETAILS);
  }
}

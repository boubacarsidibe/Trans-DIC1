package sn.ept.transdic1.events.receiver;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.snmp4j.PDU;
import org.snmp4j.PDUv1;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.TimeTicks;
import org.snmp4j.smi.VariableBinding;
import sn.ept.transdic1.events.domain.EventSeverity;
import sn.ept.transdic1.events.domain.EventSource;

/** Traduction d'une notification SNMP en événement : OID, titre, sévérité, variables. */
class TrapDecoderTests {

  @Test
  void décodeUnLinkDownV2c() {
    PDU pdu = new PDU();
    pdu.setType(PDU.TRAP);
    pdu.add(new VariableBinding(SnmpConstants.sysUpTime, new TimeTicks(1234)));
    pdu.add(new VariableBinding(SnmpConstants.snmpTrapOID, SnmpConstants.linkDown));
    pdu.add(new VariableBinding(new OID("1.3.6.1.2.1.2.2.1.1.3"), new Integer32(3)));

    ReceivedEvent event = TrapDecoder.decode(pdu, "10.0.0.1", Instant.EPOCH);

    assertThat(event.source()).isEqualTo(EventSource.SNMP_TRAP);
    assertThat(event.trapOid()).isEqualTo("1.3.6.1.6.3.1.1.5.3");
    assertThat(event.title()).isEqualTo("linkDown (interface coupée)");
    assertThat(event.severity()).isEqualTo(EventSeverity.ERROR);
    assertThat(event.details()).contains("1.3.6.1.2.1.2.2.1.1.3 = 3");
    assertThat(event.sourceAddress()).isEqualTo("10.0.0.1");
  }

  @Test
  void convertitUnTrapV1GénériqueEnOidV2() {
    PDUv1 pdu = new PDUv1();
    pdu.setType(PDU.V1TRAP);
    pdu.setEnterprise(new OID("1.3.6.1.4.1.9"));
    pdu.setGenericTrap(PDUv1.AUTHENTICATIONFAILURE);

    ReceivedEvent event = TrapDecoder.decode(pdu, "10.0.0.1", Instant.EPOCH);

    assertThat(event.trapOid()).isEqualTo("1.3.6.1.6.3.1.1.5.5");
    assertThat(event.severity()).isEqualTo(EventSeverity.WARNING);
  }

  @Test
  void convertitUnTrapV1SpécifiqueSelonLaRfc3584() {
    PDUv1 pdu = new PDUv1();
    pdu.setType(PDU.V1TRAP);
    pdu.setEnterprise(new OID("1.3.6.1.4.1.14988"));
    pdu.setGenericTrap(PDUv1.ENTERPRISE_SPECIFIC);
    pdu.setSpecificTrap(7);

    ReceivedEvent event = TrapDecoder.decode(pdu, "10.0.0.1", Instant.EPOCH);

    assertThat(event.trapOid()).isEqualTo("1.3.6.1.4.1.14988.0.7");
    assertThat(event.title()).isEqualTo("Notification 1.3.6.1.4.1.14988.0.7");
    assertThat(event.severity()).isEqualTo(EventSeverity.NOTICE);
  }

  @Test
  void bornesEtCaractèresDeContrôleDansLesVariables() {
    PDU pdu = new PDU();
    pdu.setType(PDU.TRAP);
    pdu.add(new VariableBinding(SnmpConstants.snmpTrapOID, new OID("1.3.6.1.4.1.1.2")));
    for (int i = 0; i < 100; i++) {
      pdu.add(
          new VariableBinding(
              new OID("1.3.6.1.4.1.1.3." + i),
              new OctetString("v\r\nINJECTION " + "y".repeat(80))));
    }

    ReceivedEvent event = TrapDecoder.decode(pdu, "10.0.0.1", Instant.EPOCH);

    assertThat(event.details().length()).isLessThanOrEqualTo(4096);
    assertThat(event.details()).doesNotContain("\r");
  }
}

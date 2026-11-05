package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import org.snmp4j.security.SecurityModels;
import org.snmp4j.security.SecurityProtocols;
import org.snmp4j.security.USM;
import org.snmp4j.security.UsmUser;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.TimeTicks;
import org.snmp4j.smi.UdpAddress;
import org.snmp4j.smi.VariableBinding;
import org.snmp4j.transport.DefaultUdpTransportMapping;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import sn.ept.transdic1.events.receiver.SnmpTrapReceiver;
import sn.ept.transdic1.events.receiver.SyslogReceiver;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.EquipmentStatus;
import sn.ept.transdic1.inventory.domain.EquipmentType;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.SiteRepository;

/**
 * Récepteurs réels sur des ports éphémères de 127.0.0.1 : un vrai émetteur SNMP4J (v2c et v3) et un
 * vrai datagramme syslog. La source des tests est 127.0.0.1 : un équipement à cette adresse est
 * créé pour chaque test et supprimé ensuite.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(
    properties = {
      "events.snmp-trap.enabled=true",
      "events.snmp-trap.port=0",
      "events.snmp-trap.bind-address=127.0.0.1",
      "events.snmp-trap.community=env:EVENTS_TEST_TRAP_COMMUNITY",
      "EVENTS_TEST_TRAP_COMMUNITY=tdic-trap-secret",
      "events.snmp-trap.v3.username=trapuser",
      "events.snmp-trap.v3.auth-reference=env:EVENTS_TEST_V3_AUTH",
      "events.snmp-trap.v3.priv-reference=env:EVENTS_TEST_V3_PRIV",
      "EVENTS_TEST_V3_AUTH=auth-pass-12345",
      "EVENTS_TEST_V3_PRIV=priv-pass-12345",
      "events.syslog.enabled=true",
      "events.syslog.port=0",
      "events.syslog.bind-address=127.0.0.1",
      "events.source-cache-seconds=0"
    })
class EventReceiverTests {
  @Autowired private SnmpTrapReceiver traps;
  @Autowired private SyslogReceiver syslog;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private MeterRegistry meters;

  private Site site;
  private Equipment equipment;

  @BeforeEach
  void data() {
    // Un autre test a pu laisser un équipement à cette adresse (adresse unique) : on repart de
    // zéro.
    jdbc.update("DELETE FROM equipment WHERE management_address = '127.0.0.1'");
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    site = sites.save(new Site("EVR-" + suffix, "Site récepteurs"));
    equipment =
        equipments.saveAndFlush(
            new Equipment(
                "EVR-EQ-" + suffix,
                "Routeur local",
                "127.0.0.1",
                EquipmentType.ROUTER,
                EquipmentStatus.UP,
                site));
  }

  @AfterEach
  void clean() {
    jdbc.update("DELETE FROM equipment WHERE site_id = ?", site.getId());
    jdbc.update("DELETE FROM site WHERE id = ?", site.getId());
  }

  private int stored(String source) {
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM network_event WHERE equipment_id = ? AND source = ?",
            Integer.class,
            equipment.getId(),
            source);
    return count == null ? 0 : count;
  }

  private double dropped(String reason) {
    var counter = meters.find("events.dropped").tag("reason", reason).counter();
    return counter == null ? 0 : counter.count();
  }

  private static boolean await(BooleanSupplier condition, Duration timeout)
      throws InterruptedException {
    long end = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < end) {
      if (condition.getAsBoolean()) {
        return true;
      }
      Thread.sleep(50);
    }
    return condition.getAsBoolean();
  }

  private void sendV2cTrap(String community) throws IOException {
    try (Snmp snmp = new Snmp(new DefaultUdpTransportMapping())) {
      snmp.listen();
      var target = new CommunityTarget<UdpAddress>();
      target.setCommunity(new OctetString(community));
      target.setVersion(SnmpConstants.version2c);
      target.setAddress(new UdpAddress(InetAddress.getLoopbackAddress(), traps.port()));
      PDU pdu = new PDU();
      pdu.setType(PDU.TRAP);
      pdu.add(new VariableBinding(SnmpConstants.sysUpTime, new TimeTicks(42)));
      pdu.add(new VariableBinding(SnmpConstants.snmpTrapOID, SnmpConstants.linkDown));
      pdu.add(new VariableBinding(new OID("1.3.6.1.2.1.2.2.1.1.2"), new Integer32(2)));
      snmp.send(pdu, target);
    }
  }

  @Test
  void lesRécepteursÉcoutentSurDesPortsÉphémères() {
    assertThat(traps.port()).isPositive();
    assertThat(syslog.port()).isPositive();
    assertThat(traps.isRunning()).isTrue();
    assertThat(syslog.isRunning()).isTrue();
  }

  @Test
  void unTrapV2cDUneSourceConnueAvecLaBonneCommunautéEstStocké() throws Exception {
    sendV2cTrap("tdic-trap-secret");

    assertThat(await(() -> stored("SNMP_TRAP") == 1, Duration.ofSeconds(10))).isTrue();
    String oid =
        jdbc.queryForObject(
            "SELECT trap_oid FROM network_event WHERE equipment_id = ?",
            String.class,
            equipment.getId());
    assertThat(oid).isEqualTo("1.3.6.1.6.3.1.1.5.3");
  }

  @Test
  void unTrapAvecUneMauvaiseCommunautéEstRejetéEtCompté() throws Exception {
    double before = dropped("bad_community");

    sendV2cTrap("mauvaise-communaute");

    assertThat(await(() -> dropped("bad_community") > before, Duration.ofSeconds(10))).isTrue();
    assertThat(stored("SNMP_TRAP")).isZero();
  }

  @Test
  void unTrapDUneSourceInconnueEstRejetéEtCompté() throws Exception {
    jdbc.update("DELETE FROM equipment WHERE id = ?", equipment.getId());
    double before = dropped("unknown_source");

    sendV2cTrap("tdic-trap-secret");

    assertThat(await(() -> dropped("unknown_source") > before, Duration.ofSeconds(10))).isTrue();
    Integer total =
        jdbc.queryForObject(
            "SELECT count(*) FROM network_event WHERE source_address = '127.0.0.1'", Integer.class);
    assertThat(total).isZero();
  }

  @Test
  void unTrapV3DeLUtilisateurConfiguréEstAccepté() throws Exception {
    var usm =
        new USM(SecurityProtocols.getInstance(), new OctetString(MPv3.createLocalEngineID()), 0);
    SecurityProtocols.getInstance().addAuthenticationProtocol(new AuthSHA());
    SecurityProtocols.getInstance().addPrivacyProtocol(new PrivAES128());
    var dispatcher = new org.snmp4j.MessageDispatcherImpl();
    dispatcher.addMessageProcessingModel(new MPv3(usm));
    try (Snmp snmp = new Snmp(dispatcher, new DefaultUdpTransportMapping())) {
      usm.addUser(
          new OctetString("trapuser"),
          new UsmUser(
              new OctetString("trapuser"),
              AuthSHA.ID,
              new OctetString("auth-pass-12345"),
              PrivAES128.ID,
              new OctetString("priv-pass-12345")));
      snmp.listen();
      var target = new UserTarget<UdpAddress>();
      target.setVersion(SnmpConstants.version3);
      target.setSecurityLevel(SecurityLevel.AUTH_PRIV);
      target.setSecurityName(new OctetString("trapuser"));
      target.setAddress(new UdpAddress(InetAddress.getLoopbackAddress(), traps.port()));
      ScopedPDU pdu = new ScopedPDU();
      pdu.setType(PDU.TRAP);
      pdu.add(new VariableBinding(SnmpConstants.sysUpTime, new TimeTicks(42)));
      pdu.add(new VariableBinding(SnmpConstants.snmpTrapOID, SnmpConstants.authenticationFailure));
      snmp.send(pdu, target);
    }
    assertThat(SecurityModels.getInstance()).isNotNull();

    assertThat(await(() -> stored("SNMP_TRAP") == 1, Duration.ofSeconds(10))).isTrue();
  }

  @Test
  void unDatagrammeSyslogEstAnalyséEtStocké() throws Exception {
    byte[] data =
        "<11>Nov  5 10:00:00 routeur1 kernel: Interface ether1 down"
            .getBytes(StandardCharsets.UTF_8);
    try (var socket = new DatagramSocket()) {
      socket.send(
          new DatagramPacket(data, data.length, InetAddress.getLoopbackAddress(), syslog.port()));
    }

    assertThat(await(() -> stored("SYSLOG") == 1, Duration.ofSeconds(10))).isTrue();
    var row =
        jdbc.queryForMap(
            "SELECT severity, app_name, hostname, title FROM network_event WHERE equipment_id = ?",
            equipment.getId());
    assertThat(row.get("severity")).isEqualTo("ERROR");
    assertThat(row.get("app_name")).isEqualTo("kernel");
    assertThat(row.get("hostname")).isEqualTo("routeur1");
    assertThat((String) row.get("title")).contains("Interface ether1 down");
  }
}

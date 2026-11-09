package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManagerFactory;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.snmp4j.CommunityTarget;
import org.snmp4j.PDU;
import org.snmp4j.PDUv1;
import org.snmp4j.ScopedPDU;
import org.snmp4j.Snmp;
import org.snmp4j.UserTarget;
import org.snmp4j.event.ResponseEvent;
import org.snmp4j.mp.MPv3;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.security.AuthSHA;
import org.snmp4j.security.PrivAES128;
import org.snmp4j.security.SecurityLevel;
import org.snmp4j.security.SecurityProtocols;
import org.snmp4j.security.USM;
import org.snmp4j.security.UsmUser;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.IpAddress;
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
import sn.ept.transdic1.alerting.domain.Severity;
import sn.ept.transdic1.events.domain.EventRule;
import sn.ept.transdic1.events.domain.EventSeverity;
import sn.ept.transdic1.events.domain.EventSource;
import sn.ept.transdic1.events.receiver.ReceivedEvent;
import sn.ept.transdic1.events.receiver.SnmpTrapReceiver;
import sn.ept.transdic1.events.receiver.SyslogReceiver;
import sn.ept.transdic1.events.repository.EventRuleRepository;
import sn.ept.transdic1.events.service.ActiveEventRules;
import sn.ept.transdic1.events.service.EventSourceDirectory;
import sn.ept.transdic1.events.service.NetworkEventIngestionService;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.EquipmentStatus;
import sn.ept.transdic1.inventory.domain.EquipmentType;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.SiteRepository;

/**
 * Récepteurs réels sur des ports éphémères (SNMP sur 127.0.0.1, syslog sur toutes les interfaces
 * IPv4 et IPv6 de l'hôte), avec les caches de production (sources et règles, 30 s) : un vrai
 * émetteur SNMP4J (v1, v2c, v3, inform) et de vrais datagrammes syslog. Les équipements de test
 * sont à 127.0.0.1 et ::1 ; seules les lignes créées par cette classe sont supprimées.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = EventReceiverTests.RECEIVER_PROPERTIES)
class EventReceiverTests {
  /** Propriétés partagées avec EventOrderingTests (même contexte Spring). */
  static final String RECEIVER_PROPERTIES =
      """
      events.snmp-trap.enabled=true
      events.snmp-trap.port=0
      events.snmp-trap.bind-address=127.0.0.1
      events.snmp-trap.community=env:EVENTS_TEST_TRAP_COMMUNITY
      EVENTS_TEST_TRAP_COMMUNITY=tdic-trap-secret
      events.snmp-trap.v3.username=trapuser
      events.snmp-trap.v3.auth-reference=env:EVENTS_TEST_V3_AUTH
      events.snmp-trap.v3.priv-reference=env:EVENTS_TEST_V3_PRIV
      EVENTS_TEST_V3_AUTH=auth-pass-12345
      EVENTS_TEST_V3_PRIV=priv-pass-12345
      events.syslog.enabled=true
      events.syslog.port=0
      events.syslog.bind-address=::
      events.source-cache-seconds=30
      events.rule-cache-seconds=30
      """;

  @Autowired private SnmpTrapReceiver traps;
  @Autowired private SyslogReceiver syslog;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private EventRuleRepository rules;
  @Autowired private EventSourceDirectory directory;
  @Autowired private ActiveEventRules activeRules;
  @Autowired private NetworkEventIngestionService ingestion;
  @Autowired private EntityManagerFactory entityManagerFactory;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private MeterRegistry meters;

  private Site site;
  private Equipment equipment;
  private UUID ruleId;

  @BeforeEach
  void data() {
    // Un test interrompu de cette classe a pu laisser son équipement (adresse unique).
    jdbc.update(
        "DELETE FROM equipment WHERE management_address IN ('127.0.0.1', '::1')"
            + " AND inventory_code LIKE 'EVR-EQ%'");
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    site = sites.save(new Site("EVR-" + suffix, "Site récepteurs"));
    equipment = equipment("EVR-EQ-" + suffix, "127.0.0.1");
    directory.invalidate();
  }

  private Equipment equipment(String code, String address) {
    return equipments.saveAndFlush(
        new Equipment(
            code, "Routeur local", address, EquipmentType.ROUTER, EquipmentStatus.UP, site));
  }

  @AfterEach
  void clean() {
    if (ruleId != null) {
      jdbc.update("DELETE FROM event_rule WHERE id = ?", ruleId);
      activeRules.evict();
    }
    jdbc.update("DELETE FROM equipment WHERE site_id = ?", site.getId());
    jdbc.update("DELETE FROM site WHERE id = ?", site.getId());
    directory.invalidate();
  }

  private int stored(Equipment target, String source) {
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM network_event WHERE equipment_id = ? AND source = ?",
            Integer.class,
            target.getId(),
            source);
    return count == null ? 0 : count;
  }

  private int stored(String source) {
    return stored(equipment, source);
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

  private CommunityTarget<UdpAddress> target(String community, int version) {
    var target = new CommunityTarget<UdpAddress>();
    target.setCommunity(new OctetString(community));
    target.setVersion(version);
    target.setAddress(new UdpAddress(InetAddress.getLoopbackAddress(), traps.port()));
    target.setTimeout(1_500);
    target.setRetries(0);
    return target;
  }

  private static PDU linkDown(int type) {
    PDU pdu = new PDU();
    pdu.setType(type);
    pdu.add(new VariableBinding(SnmpConstants.sysUpTime, new TimeTicks(42)));
    pdu.add(new VariableBinding(SnmpConstants.snmpTrapOID, SnmpConstants.linkDown));
    pdu.add(new VariableBinding(new OID("1.3.6.1.2.1.2.2.1.1.2"), new Integer32(2)));
    return pdu;
  }

  private void sendV2cTrap(String community) throws IOException {
    try (Snmp snmp = new Snmp(new DefaultUdpTransportMapping())) {
      snmp.listen();
      snmp.send(linkDown(PDU.TRAP), target(community, SnmpConstants.version2c));
    }
  }

  private ResponseEvent<UdpAddress> sendInform() throws IOException {
    try (Snmp snmp = new Snmp(new DefaultUdpTransportMapping())) {
      snmp.listen();
      return snmp.send(linkDown(PDU.INFORM), target("tdic-trap-secret", SnmpConstants.version2c));
    }
  }

  private void sendSyslog(InetAddress to, String line) throws IOException {
    byte[] data = line.getBytes(StandardCharsets.UTF_8);
    try (var socket = new DatagramSocket()) {
      socket.send(new DatagramPacket(data, data.length, to, syslog.port()));
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
  void unTrapV1EstDécodéEtStocké() throws Exception {
    try (Snmp snmp = new Snmp(new DefaultUdpTransportMapping())) {
      snmp.listen();
      PDUv1 pdu = new PDUv1();
      pdu.setType(PDU.V1TRAP);
      pdu.setEnterprise(new OID("1.3.6.1.4.1.9"));
      pdu.setGenericTrap(PDUv1.LINKDOWN);
      pdu.setAgentAddress(new IpAddress("127.0.0.1"));
      pdu.setTimestamp(42);
      pdu.add(new VariableBinding(new OID("1.3.6.1.2.1.2.2.1.1.2"), new Integer32(2)));
      snmp.send(pdu, target("tdic-trap-secret", SnmpConstants.version1));
    }

    assertThat(await(() -> stored("SNMP_TRAP") == 1, Duration.ofSeconds(10))).isTrue();
    String oid =
        jdbc.queryForObject(
            "SELECT trap_oid FROM network_event WHERE equipment_id = ?",
            String.class,
            equipment.getId());
    assertThat(oid).isEqualTo("1.3.6.1.6.3.1.1.5.3");
  }

  @Test
  void unInformDUneSourceConnueEstAcquittéEtStocké() throws Exception {
    ResponseEvent<UdpAddress> response = sendInform();

    assertThat(response.getResponse()).isNotNull();
    assertThat(response.getResponse().getType()).isEqualTo(PDU.RESPONSE);
    assertThat(await(() -> stored("SNMP_TRAP") == 1, Duration.ofSeconds(10))).isTrue();
  }

  @Test
  void unInformDUneSourceInconnueNEstPasAcquitté() throws Exception {
    jdbc.update("DELETE FROM equipment WHERE id = ?", equipment.getId());
    directory.invalidate();
    double before = dropped("unknown_source");

    ResponseEvent<UdpAddress> response = sendInform();

    assertThat(response.getResponse()).isNull();
    assertThat(dropped("unknown_source")).isGreaterThan(before);
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
    directory.invalidate();
    double before = dropped("unknown_source");

    sendV2cTrap("tdic-trap-secret");

    assertThat(await(() -> dropped("unknown_source") > before, Duration.ofSeconds(10))).isTrue();
    Integer total =
        jdbc.queryForObject(
            "SELECT count(*) FROM network_event WHERE source_address = '127.0.0.1'"
                + " AND received_at > now() - interval '1 minute'",
            Integer.class);
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

    assertThat(await(() -> stored("SNMP_TRAP") == 1, Duration.ofSeconds(10))).isTrue();
    String oid =
        jdbc.queryForObject(
            "SELECT trap_oid FROM network_event WHERE equipment_id = ?",
            String.class,
            equipment.getId());
    assertThat(oid).isEqualTo("1.3.6.1.6.3.1.1.5.5");
  }

  @Test
  void unDatagrammeSyslogEstAnalyséEtStocké() throws Exception {
    sendSyslog(
        InetAddress.getByName("127.0.0.1"),
        "<11>Nov  5 10:00:00 routeur1 kernel: Interface ether1 down");

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

  @Test
  void unÉquipementIpv6SaisiEnFormeCompresséeEstReconnu() throws Exception {
    // Java présente la source comme 0:0:0:0:0:0:0:1 ; l'inventaire contient « ::1 ».
    Equipment v6 = equipment("EVR-EQ6-" + UUID.randomUUID().toString().substring(0, 8), "::1");
    directory.invalidate();

    sendSyslog(InetAddress.getByName("::1"), "<12>Nov  5 10:00:00 routeur6 bgp: peer down");

    assertThat(await(() -> stored(v6, "SYSLOG") == 1, Duration.ofSeconds(10))).isTrue();
    assertThat(stored("SYSLOG")).isZero();
  }

  @Test
  void lesRèglesEtLInventaireNeSontPasRelusÀChaqueÉvénement() {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    var rule = new EventRule("Lien coupé EVR " + suffix, Severity.WARNING);
    rule.setSource(EventSource.SYSLOG);
    rule.setMessageRegex("jamais-vu-" + suffix);
    rule.setSiteIds(Set.of(site.getId()));
    rule.setEquipmentIds(Set.of(equipment.getId()));
    rule.setActive(true);
    rules.saveAndFlush(rule);
    ruleId = rule.getId();
    activeRules.evict();

    var stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    stats.setStatisticsEnabled(true);
    try {
      ingestion.ingest(syslogEvent());
      stats.clear();
      for (int i = 0; i < 20; i++) {
        ingestion.ingest(syslogEvent());
      }
      assertThat(stats.getEntityStatistics(EventRule.class.getName()).getLoadCount()).isZero();
      assertThat(stats.getEntityStatistics(Equipment.class.getName()).getLoadCount()).isZero();
      assertThat(stats.getCollectionFetchCount()).isZero();
      assertThat(
              stats
                  .getEntityStatistics("sn.ept.transdic1.events.domain.NetworkEvent")
                  .getInsertCount())
          .isEqualTo(20);
    } finally {
      stats.setStatisticsEnabled(false);
    }
    assertThat(stored("SYSLOG")).isEqualTo(21);
  }

  private static ReceivedEvent syslogEvent() {
    return new ReceivedEvent(
        EventSource.SYSLOG,
        "127.0.0.1",
        EventSeverity.INFO,
        "kernel : rien",
        "rien",
        null,
        null,
        "kernel",
        1,
        Instant.now());
  }
}

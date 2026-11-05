package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.alerting.domain.AlertChangedEvent;
import sn.ept.transdic1.alerting.domain.AlertEventType;
import sn.ept.transdic1.alerting.domain.AlertState;
import sn.ept.transdic1.alerting.domain.AlertType;
import sn.ept.transdic1.alerting.domain.Maintenance;
import sn.ept.transdic1.alerting.domain.MaintenanceState;
import sn.ept.transdic1.alerting.domain.Severity;
import sn.ept.transdic1.alerting.repository.AlertEventRepository;
import sn.ept.transdic1.alerting.repository.AlertRepository;
import sn.ept.transdic1.alerting.repository.MaintenanceRepository;
import sn.ept.transdic1.events.domain.EventRule;
import sn.ept.transdic1.events.domain.EventSeverity;
import sn.ept.transdic1.events.domain.EventSource;
import sn.ept.transdic1.events.receiver.ReceivedEvent;
import sn.ept.transdic1.events.repository.EventRuleRepository;
import sn.ept.transdic1.events.repository.NetworkEventRepository;
import sn.ept.transdic1.events.service.NetworkEventIngestionService;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.EquipmentStatus;
import sn.ept.transdic1.inventory.domain.EquipmentType;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.SiteRepository;
import sn.ept.transdic1.security.domain.AppUser;
import sn.ept.transdic1.security.domain.Role;
import sn.ept.transdic1.security.repository.UserRepository;

/**
 * Événements réseau vers alertes EVENT : stockage, source inconnue ignorée, ouverture,
 * déduplication par (équipement, règle), résolution par un événement de retour, maintenance
 * respectée.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@RecordApplicationEvents
@TestPropertySource(properties = "events.source-cache-seconds=0")
class EventAlertTests {
  private static final String LINK_DOWN = "1.3.6.1.6.3.1.1.5.3";
  private static final String LINK_UP = "1.3.6.1.6.3.1.1.5.4";

  @Autowired private NetworkEventIngestionService ingestion;
  @Autowired private EventRuleRepository rules;
  @Autowired private NetworkEventRepository networkEvents;
  @Autowired private AlertRepository alerts;
  @Autowired private AlertEventRepository alertEvents;
  @Autowired private MaintenanceRepository maintenances;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private UserRepository users;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ApplicationEvents published;

  private Site site;
  private Equipment equipment;
  private String address;
  private EventRule linkRule;

  @BeforeEach
  void data() {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    site = sites.save(new Site("EVA-" + suffix, "Site événements"));
    address =
        "10.241."
            + ThreadLocalRandom.current().nextInt(1, 254)
            + "."
            + ThreadLocalRandom.current().nextInt(1, 254);
    equipment =
        equipments.saveAndFlush(
            new Equipment(
                "EVA-EQ-" + suffix,
                "Routeur de test",
                address,
                EquipmentType.ROUTER,
                EquipmentStatus.UP,
                site));
    linkRule = new EventRule("Lien coupé " + suffix, Severity.CRITICAL);
    linkRule.setSource(EventSource.SNMP_TRAP);
    linkRule.setOidPrefix(LINK_DOWN);
    linkRule.setResolveOid(LINK_UP);
    linkRule.setDedupWindowSeconds(300);
    linkRule.setEquipmentIds(Set.of(equipment.getId()));
    linkRule.setActive(true);
    rules.saveAndFlush(linkRule);
  }

  private ReceivedEvent trap(String from, String oid, Instant at) {
    return new ReceivedEvent(
        EventSource.SNMP_TRAP,
        from,
        oid.equals(LINK_DOWN) ? EventSeverity.ERROR : EventSeverity.NOTICE,
        oid.equals(LINK_DOWN) ? "linkDown (interface coupée)" : "linkUp (interface rétablie)",
        "ifIndex = 3",
        oid,
        null,
        null,
        null,
        at);
  }

  private List<sn.ept.transdic1.alerting.domain.Alert> eventAlerts() {
    return alerts.findAll().stream()
        .filter(a -> a.getEquipment().getId().equals(equipment.getId()))
        .filter(a -> a.getType() == AlertType.EVENT)
        .toList();
  }

  private long notifications(AlertEventType type) {
    return published.stream(AlertChangedEvent.class).filter(e -> e.type() == type).count();
  }

  @Test
  void unÉvénementDuneSourceConnueEstStocké() {
    int stored = ingestion.ingest(trap(address, "1.3.6.1.4.1.9.9.1", Instant.now()));

    assertThat(stored).isEqualTo(1);
    var saved =
        networkEvents.findAll().stream()
            .filter(e -> e.getEquipment().getId().equals(equipment.getId()))
            .toList();
    assertThat(saved).hasSize(1);
    assertThat(saved.get(0).getTrapOid()).isEqualTo("1.3.6.1.4.1.9.9.1");
    assertThat(saved.get(0).getSourceAddress()).isEqualTo(address);
  }

  @Test
  void uneSourceInconnueNEstJamaisStockée() {
    long before = networkEvents.count();

    assertThat(ingestion.ingest(trap("10.255.255.254", LINK_DOWN, Instant.now()))).isZero();
    assertThat(networkEvents.count()).isEqualTo(before);
  }

  @Test
  void unÉquipementNonSurveilléEstTraitéCommeInconnu() {
    equipment.setMonitoringEnabled(false);
    equipments.saveAndFlush(equipment);

    assertThat(ingestion.ingest(trap(address, LINK_DOWN, Instant.now()))).isZero();
  }

  @Test
  void ouvreUneAlerteEventDédupliquéeEtLaRésoutParLinkUp() {
    Instant t0 = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    ingestion.ingest(trap(address, LINK_DOWN, t0));
    ingestion.ingest(trap(address, LINK_DOWN, t0.plusSeconds(5)));

    var opened = eventAlerts();
    assertThat(opened).hasSize(1);
    var alert = opened.get(0);
    assertThat(alert.getState()).isEqualTo(AlertState.OPEN);
    assertThat(alert.getSeverity()).isEqualTo(Severity.CRITICAL);
    assertThat(alert.getEventRuleId()).isEqualTo(linkRule.getId());
    assertThat(alert.getMessage()).contains(linkRule.getName()).contains("linkDown");
    assertThat(notifications(AlertEventType.OPENED)).isEqualTo(1);

    ingestion.ingest(trap(address, LINK_UP, t0.plusSeconds(60)));

    alert = alerts.findById(alert.getId()).orElseThrow();
    assertThat(alert.getState()).isEqualTo(AlertState.RESOLVED);
    assertThat(notifications(AlertEventType.RESOLVED)).isEqualTo(1);
  }

  @Test
  void uneRechuteDansLaFenêtreRouvreSansNouvelleNotification() {
    Instant t0 = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    ingestion.ingest(trap(address, LINK_DOWN, t0));
    ingestion.ingest(trap(address, LINK_UP, t0.plusSeconds(10)));
    ingestion.ingest(trap(address, LINK_DOWN, t0.plusSeconds(20)));

    var all = eventAlerts();
    assertThat(all).hasSize(1);
    assertThat(all.get(0).getState()).isEqualTo(AlertState.OPEN);
    assertThat(notifications(AlertEventType.OPENED)).isEqualTo(1);

    // Hors de la fenêtre, la rechute notifie de nouveau.
    ingestion.ingest(trap(address, LINK_UP, t0.plusSeconds(400)));
    ingestion.ingest(trap(address, LINK_DOWN, t0.plusSeconds(700)));
    assertThat(notifications(AlertEventType.OPENED)).isEqualTo(2);
  }

  @Test
  void uneRègleInactiveOuHorsPortéeNOuvreRien() {
    Site other =
        sites.save(new Site("EVO-" + UUID.randomUUID().toString().substring(0, 8), "Autre"));
    linkRule.setEquipmentIds(Set.of());
    linkRule.setSiteIds(Set.of(other.getId()));
    rules.saveAndFlush(linkRule);
    ingestion.ingest(trap(address, LINK_DOWN, Instant.now()));
    assertThat(eventAlerts()).isEmpty();

    linkRule.setSiteIds(Set.of());
    linkRule.setEquipmentIds(Set.of(equipment.getId()));
    linkRule.setActive(false);
    rules.saveAndFlush(linkRule);
    ingestion.ingest(trap(address, LINK_DOWN, Instant.now()));
    assertThat(eventAlerts()).isEmpty();
  }

  @Test
  void enMaintenanceLAlerteEstTracéeSansNotification() {
    AppUser planner =
        users.save(
            new AppUser(
                "eva-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID() + "@example.test",
                "x",
                Role.ADMIN));
    var maintenance =
        new Maintenance(
            planner,
            Instant.now().minusSeconds(600),
            Instant.now().plusSeconds(3600),
            "Travaux",
            MaintenanceState.IN_PROGRESS);
    maintenance.setEquipment(equipment);
    maintenances.saveAndFlush(maintenance);

    ingestion.ingest(trap(address, LINK_DOWN, Instant.now()));

    var opened = eventAlerts();
    assertThat(opened).hasSize(1);
    assertThat(notifications(AlertEventType.OPENED)).isZero();
    assertThat(alertEvents.findByAlertIdOrderByOccurredAtAsc(opened.get(0).getId()))
        .anyMatch(e -> e.getComment() != null && e.getComment().contains("Maintenance"));
  }

  @Test
  void laContrainteRefuseDeuxAlertesEventActivesPourLaMêmeRègle() {
    ingestion.ingest(trap(address, LINK_DOWN, Instant.now()));
    alerts.flush();

    Integer duplicates =
        jdbc.queryForObject(
            "SELECT count(*) FROM pg_indexes WHERE indexname = 'uq_alert_one_active_per_event_rule'",
            Integer.class);
    assertThat(duplicates).isEqualTo(1);
  }
}

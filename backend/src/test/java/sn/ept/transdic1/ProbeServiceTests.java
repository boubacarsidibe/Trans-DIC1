package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import sn.ept.transdic1.collection.probe.Probe;
import sn.ept.transdic1.collection.probe.ProbeRequest;
import sn.ept.transdic1.collection.probe.ProbeResult;
import sn.ept.transdic1.collection.probe.ProbeService;
import sn.ept.transdic1.collection.probe.ProbeTargetGuard;
import sn.ept.transdic1.collection.service.MeasureIngestionService;
import sn.ept.transdic1.common.scheduling.JobGuard;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.EquipmentStatus;
import sn.ept.transdic1.inventory.domain.EquipmentType;
import sn.ept.transdic1.inventory.domain.MonitoredService;
import sn.ept.transdic1.inventory.domain.ServiceType;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.MonitoredServiceRepository;
import sn.ept.transdic1.inventory.repository.SiteRepository;
import sn.ept.transdic1.monitoring.domain.Metric;
import sn.ept.transdic1.monitoring.repository.MetricRepository;

/**
 * Exécuteur planifié des sondes sans agent, avec de fausses sondes : échéances, reprogrammation
 * sans rattrapage, cible interdite stockée comme un échec, isolement des erreurs, types ignorés,
 * service désactivé en cours de route et test manuel sans stockage, sur un vrai PostgreSQL.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ProbeServiceTests {
  @Autowired private ProbeTargetGuard targets;
  @Autowired private MonitoredServiceRepository services;
  @Autowired private MeasureIngestionService ingestion;
  @Autowired private TransactionTemplate transaction;
  @Autowired private JobGuard jobGuard;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private MetricRepository metrics;
  @Autowired private JdbcTemplate jdbc;

  /** Fausse sonde : enregistre les cibles reçues, lève une exception pour les hôtes piégés. */
  private static final class FakeProbe implements Probe {
    private final ServiceType type;
    private final List<String> calls = Collections.synchronizedList(new ArrayList<>());
    private final List<ProbeRequest> requests = Collections.synchronizedList(new ArrayList<>());
    private final Set<String> exploding = new HashSet<>();
    private Runnable onRun = () -> {};

    FakeProbe(ServiceType type) {
      this.type = type;
    }

    @Override
    public ServiceType type() {
      return type;
    }

    @Override
    public ProbeResult run(ProbeRequest request) {
      String host = request.address().getHostAddress();
      calls.add(host);
      requests.add(request);
      onRun.run();
      if (exploding.contains(host)) {
        throw new IllegalStateException("panne simulée");
      }
      return ProbeResult.up(7);
    }
  }

  private final FakeProbe tcp = new FakeProbe(ServiceType.TCP_PORT);
  private final FakeProbe http = new FakeProbe(ServiceType.HTTP);
  private final FakeProbe https = new FakeProbe(ServiceType.HTTPS);
  private final FakeProbe icmp = new FakeProbe(ServiceType.ICMP);
  private final FakeProbe tls = new FakeProbe(ServiceType.TLS_CERT);
  private final FakeProbe dns = new FakeProbe(ServiceType.DNS);
  private final FakeProbe other = new FakeProbe(ServiceType.OTHER);

  private Instant now;
  private ProbeService probeService;
  private Site site;
  private final AtomicInteger counter = new AtomicInteger();

  @BeforeEach
  void data() {
    now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    // Isole le test des services laissés par d'autres suites (annulé avec la transaction du test) :
    // seuls les services créés ici sont exécutés, ce qui permet des comptes exacts.
    jdbc.update("UPDATE monitored_service SET active = FALSE WHERE active");
    probeService = probeService(500);
    site =
        sites.save(
            new Site("PSV-" + UUID.randomUUID().toString().substring(0, 8), "Site des sondes"));
  }

  private ProbeService probeService(int maxPerScan) {
    return new ProbeService(
        List.of(tcp, http, https, icmp, tls, dns, other),
        targets,
        services,
        ingestion,
        transaction,
        Clock.fixed(now, ZoneOffset.UTC),
        jobGuard,
        1,
        maxPerScan);
  }

  private Equipment equipment() {
    int n = counter.incrementAndGet();
    Equipment created =
        equipments.save(
            new Equipment(
                "PSV-EQ-" + UUID.randomUUID(),
                "Serveur sondé",
                "10.243." + (Math.floorMod(UUID.randomUUID().hashCode(), 250) + 1) + "." + n,
                EquipmentType.SERVER,
                EquipmentStatus.UP,
                site));
    equipments.flush();
    return created;
  }

  private MonitoredService service(ServiceType type, Instant nextRunAt) {
    Equipment owner = equipment();
    var service = new MonitoredService(owner, "Sonde " + type, type);
    if (type == ServiceType.TCP_PORT) {
      service.setPort(22);
    }
    service.setNextRunAt(nextRunAt);
    return services.saveAndFlush(service);
  }

  private String ip(MonitoredService service) {
    return service.getEquipment().getManagementAddress();
  }

  private MonitoredService reload(MonitoredService service) {
    return services.findById(service.getId()).orElseThrow();
  }

  private List<Metric> stored(MonitoredService service, String key) {
    return metrics.findByEquipmentIdAndServiceIdAndKeyAndCollectedAtBetweenOrderByCollectedAtDesc(
        service.getEquipment().getId(),
        service.getId(),
        key,
        now.minus(1, ChronoUnit.HOURS),
        now.plus(1, ChronoUnit.HOURS),
        Pageable.unpaged());
  }

  @Test
  void runsOnlyDueActiveServices() {
    var due = service(ServiceType.TCP_PORT, now.minusSeconds(1));
    var later = service(ServiceType.TCP_PORT, now.plusSeconds(60));
    var inactive = service(ServiceType.TCP_PORT, now.minusSeconds(1));
    inactive.setActive(false);
    services.saveAndFlush(inactive);
    var neverScheduled = service(ServiceType.TCP_PORT, null);

    int executed = probeService.runDue();

    // Une échéance absente (service jamais exécuté) compte comme échue.
    assertThat(executed).isEqualTo(2);
    assertThat(tcp.calls).containsExactlyInAnyOrder(ip(due), ip(neverScheduled));
    assertThat(stored(due, "probe.up")).hasSize(1);
    assertThat(stored(due, "probe.up").get(0).getValue()).isEqualByComparingTo("100");
    assertThat(stored(neverScheduled, "probe.up")).hasSize(1);
    assertThat(stored(later, "probe.up")).isEmpty();
    assertThat(stored(inactive, "probe.up")).isEmpty();
  }

  @Test
  void setsNextRunAtFromInterval() {
    var service = service(ServiceType.ICMP, now.minusSeconds(1));
    service.setIntervalSeconds(120);
    services.saveAndFlush(service);

    assertThat(probeService.runDue()).isEqualTo(1);

    var reloaded = reload(service);
    assertThat(icmp.calls).contains(ip(service));
    assertThat(reloaded.getNextRunAt()).isEqualTo(now.plusSeconds(120));
    assertThat(reloaded.getLastCheckedAt()).isEqualTo(now);
    assertThat(reloaded.getLastUp()).isTrue();
    assertThat(reloaded.getLastLatencyMs()).isEqualTo(7);
  }

  @Test
  void longOutageRunsEachServiceOnce() {
    var service = service(ServiceType.TCP_PORT, now.minus(2, ChronoUnit.HOURS));
    service.setIntervalSeconds(60);
    services.saveAndFlush(service);

    assertThat(probeService.runDue()).isEqualTo(1);

    assertThat(tcp.calls).containsOnlyOnce(ip(service));
    assertThat(reload(service).getNextRunAt()).isEqualTo(now.plusSeconds(60));

    assertThat(probeService.runDue()).isEqualTo(0);

    assertThat(tcp.calls.stream().filter(ip(service)::equals)).hasSize(1);
    assertThat(stored(service, "probe.up")).hasSize(1);
  }

  @Test
  void forbiddenTargetIsStoredAsFailure() {
    var service = service(ServiceType.HTTP, now.minusSeconds(1));
    service.setAccessPoint("http://127.0.0.1/sante");
    services.saveAndFlush(service);

    assertThat(probeService.runDue()).isEqualTo(1);

    assertThat(http.calls).doesNotContain("127.0.0.1");
    var reloaded = reload(service);
    assertThat(reloaded.getLastUp()).isFalse();
    assertThat(reloaded.getLastError()).startsWith("Cible interdite");
    assertThat(stored(service, "probe.up")).hasSize(1);
    assertThat(stored(service, "probe.up").get(0).getValue()).isEqualByComparingTo("0");
    assertThat(reloaded.getNextRunAt()).isEqualTo(now.plusSeconds(60));
  }

  @Test
  void oneFailingProbeDoesNotStopOthers() {
    var broken = service(ServiceType.TCP_PORT, now.minusSeconds(1));
    var healthy = service(ServiceType.TCP_PORT, now.minusSeconds(1));
    tcp.exploding.add(ip(broken));

    assertThat(probeService.runDue()).isEqualTo(2);

    assertThat(tcp.calls).contains(ip(broken), ip(healthy));
    var failed = reload(broken);
    assertThat(failed.getLastUp()).isFalse();
    assertThat(failed.getLastError()).isEqualTo("Erreur interne");
    assertThat(stored(broken, "probe.up").get(0).getValue()).isEqualByComparingTo("0");
    assertThat(reload(healthy).getLastUp()).isTrue();
    assertThat(stored(healthy, "probe.up").get(0).getValue()).isEqualByComparingTo("100");
  }

  @Test
  void ignoresNonProbeTypes() {
    // DNS est désormais une sonde à part entière (ADR 0027) : seul OTHER reste ignoré.
    var otherService = service(ServiceType.OTHER, now.minusSeconds(1));

    assertThat(probeService.runDue()).isEqualTo(0);

    assertThat(other.calls).doesNotContain(ip(otherService));
    assertThat(stored(otherService, "probe.up")).isEmpty();
    assertThat(reload(otherService).getLastCheckedAt()).isNull();
  }

  @Test
  void uneSondeDnsEstExecutee() {
    var dnsService = service(ServiceType.DNS, now.minusSeconds(1));

    assertThat(probeService.runDue()).isEqualTo(1);

    assertThat(reload(dnsService).getLastCheckedAt()).isNotNull();
  }

  @Test
  void deactivatedServiceIsSkipped() {
    // Désactivé pendant l'exécution de la sonde : le résultat n'est pas enregistré.
    var service = service(ServiceType.TCP_PORT, now.minusSeconds(1));
    tcp.onRun =
        () -> {
          var current = services.findById(service.getId()).orElseThrow();
          current.setActive(false);
          services.saveAndFlush(current);
        };
    // Équipement dont la supervision est coupée : la sonde ne tourne pas du tout.
    var unmonitored = service(ServiceType.ICMP, now.minusSeconds(1));
    var owner = unmonitored.getEquipment();
    owner.setMonitoringEnabled(false);
    equipments.saveAndFlush(owner);

    assertThat(probeService.runDue()).isEqualTo(1);

    assertThat(tcp.calls).contains(ip(service));
    assertThat(stored(service, "probe.up")).isEmpty();
    assertThat(reload(service).getLastCheckedAt()).isNull();
    assertThat(icmp.calls).doesNotContain(ip(unmonitored));
    assertThat(stored(unmonitored, "probe.up")).isEmpty();
    assertThat(reload(unmonitored).getLastCheckedAt()).isNull();
  }

  @Test
  void testRunStoresNothing() {
    Instant scheduled = now.plus(Duration.ofMinutes(10));
    var service = service(ServiceType.HTTPS, scheduled);

    ProbeResult result = probeService.runOnce(service);

    assertThat(result.up()).isTrue();
    assertThat(https.calls).contains(ip(service));
    assertThat(stored(service, "probe.up")).isEmpty();
    var reloaded = reload(service);
    assertThat(reloaded.getNextRunAt()).isEqualTo(scheduled);
    assertThat(reloaded.getLastCheckedAt()).isNull();
    assertThat(reloaded.getLastUp()).isNull();
    assertThat(reloaded.getConsecutiveFailures()).isZero();
  }

  private MonitoredService web(ServiceType type, String accessPoint) {
    var service = service(type, now.minusSeconds(1));
    service.setAccessPoint(accessPoint);
    return services.saveAndFlush(service);
  }

  @Test
  void nullNextRunAtRunsOnceThenFollowsInterval() {
    var service = service(ServiceType.ICMP, null);
    service.setIntervalSeconds(90);
    services.saveAndFlush(service);

    assertThat(probeService.runDue()).isEqualTo(1);

    assertThat(icmp.calls).containsExactly(ip(service));
    assertThat(reload(service).getNextRunAt()).isEqualTo(now.plusSeconds(90));
    assertThat(probeService.runDue()).isEqualTo(0);
    assertThat(icmp.calls).hasSize(1);
  }

  @Test
  void maxPerScanCapsClaimsOldestFirst() {
    var never = service(ServiceType.TCP_PORT, null);
    var oldest = service(ServiceType.TCP_PORT, now.minus(2, ChronoUnit.HOURS));
    var recent = service(ServiceType.TCP_PORT, now.minusSeconds(1));
    ProbeService capped = probeService(2);

    assertThat(capped.runDue()).isEqualTo(2);

    assertThat(tcp.calls).containsExactlyInAnyOrder(ip(never), ip(oldest));
    assertThat(reload(recent).getNextRunAt()).isEqualTo(now.minusSeconds(1));

    assertThat(capped.runDue()).isEqualTo(1);
    assertThat(tcp.calls).containsExactlyInAnyOrder(ip(never), ip(oldest), ip(recent));
  }

  @Test
  void refusesLoopbackUrlsInAllForms() {
    var userInfo = web(ServiceType.HTTP, "http://evil@127.0.0.1/");
    var fragment = web(ServiceType.HTTP, "http://127.0.0.1#@good.example");
    var ipv6 = web(ServiceType.HTTP, "http://[::1]/");

    assertThat(probeService.runDue()).isEqualTo(3);

    assertThat(http.calls).isEmpty();
    for (var service : List.of(userInfo, fragment, ipv6)) {
      var reloaded = reload(service);
      assertThat(reloaded.getLastUp()).isFalse();
      assertThat(reloaded.getLastError()).startsWith("Cible interdite");
      assertThat(stored(service, "probe.up").get(0).getValue()).isEqualByComparingTo("0");
    }
  }

  @Test
  void passesHostPortAndPathFromUrl() {
    web(ServiceType.HTTPS, "https://10.243.200.7:8443/sante?complet=1");

    assertThat(probeService.runDue()).isEqualTo(1);

    assertThat(https.requests).hasSize(1);
    ProbeRequest request = https.requests.get(0);
    assertThat(request.address().getHostAddress()).isEqualTo("10.243.200.7");
    assertThat(request.host()).isEqualTo("10.243.200.7");
    assertThat(request.port()).isEqualTo(8443);
    assertThat(request.path()).isEqualTo("/sante?complet=1");
    assertThat(request.timeout()).isEqualTo(Duration.ofMillis(5000));
  }

  @Test
  void appliesDefaultPorts() {
    web(ServiceType.HTTP, "http://10.243.201.1/");
    web(ServiceType.HTTPS, "10.243.201.2");
    web(ServiceType.TLS_CERT, "10.243.201.3");

    assertThat(probeService.runDue()).isEqualTo(3);

    assertThat(http.requests)
        .singleElement()
        .satisfies(
            r -> {
              assertThat(r.port()).isEqualTo(80);
              assertThat(r.path()).isEqualTo("/");
            });
    assertThat(https.requests)
        .singleElement()
        .satisfies(
            r -> {
              assertThat(r.port()).isEqualTo(443);
              assertThat(r.path()).isEqualTo("/");
            });
    assertThat(tls.requests).singleElement().satisfies(r -> assertThat(r.port()).isEqualTo(443));
  }

  @Test
  void clampsTimeoutIntoAllowedRange() {
    Equipment owner = equipment();
    var tooShort = new MonitoredService(owner, "Délai nul", ServiceType.ICMP);
    tooShort.setTimeoutMs(0);
    var tooLong = new MonitoredService(owner, "Délai énorme", ServiceType.ICMP);
    tooLong.setTimeoutMs(99999);

    assertThat(probeService.runOnce(tooShort).up()).isTrue();
    assertThat(probeService.runOnce(tooLong).up()).isTrue();

    assertThat(icmp.requests)
        .extracting(ProbeRequest::timeout)
        .containsExactly(Duration.ofMillis(1), Duration.ofSeconds(30));
  }

  @Test
  void tcpWithoutPortFails() {
    var service = service(ServiceType.TCP_PORT, now.minusSeconds(1));
    service.setPort(null);
    services.saveAndFlush(service);

    assertThat(probeService.runDue()).isEqualTo(1);

    assertThat(tcp.calls).isEmpty();
    var reloaded = reload(service);
    assertThat(reloaded.getLastUp()).isFalse();
    assertThat(reloaded.getLastError()).isEqualTo("Port manquant");
    assertThat(stored(service, "probe.up").get(0).getValue()).isEqualByComparingTo("0");
  }

  @Test
  void preparationErrorBecomesFailedResult() {
    // Sans équipement ni point d'accès, préparer la cible échoue : résultat en échec, pas
    // d'exception.
    var orphan = new MonitoredService(null, "Sans équipement", ServiceType.HTTP);

    ProbeResult result = probeService.runOnce(orphan);

    assertThat(result.up()).isFalse();
    assertThat(result.error()).isEqualTo("Erreur interne");
    assertThat(http.calls).isEmpty();
  }
}

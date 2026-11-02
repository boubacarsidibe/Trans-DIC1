package sn.ept.transdic1.collection.probe;

import jakarta.annotation.PreDestroy;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import sn.ept.transdic1.collection.service.MeasureIngestionService;
import sn.ept.transdic1.common.scheduling.JobGuard;
import sn.ept.transdic1.inventory.domain.MonitoredService;
import sn.ept.transdic1.inventory.domain.ServiceType;
import sn.ept.transdic1.inventory.repository.MonitoredServiceRepository;

/**
 * Exécution planifiée des sondes sans agent : chaque service actif arrivé à échéance est testé une
 * fois par passage, son échéance suivante est fixée avant l'exécution (pas de rattrapage après une
 * longue panne, jamais deux exécutions simultanées), puis le résultat est déposé comme mesure. Une
 * sonde en échec, refusée ou défaillante ne bloque jamais les autres. Déclaré {@code @Component} :
 * il vit avec les sondes, hors des paquets {@code service}.
 */
@Component
public class ProbeService {
  private static final Logger LOG = LoggerFactory.getLogger(ProbeService.class);

  /** Types de service exécutés par les sondes ; DNS et OTHER sont ignorés. */
  static final Set<ServiceType> PROBE_TYPES =
      EnumSet.of(
          ServiceType.TCP_PORT,
          ServiceType.HTTP,
          ServiceType.HTTPS,
          ServiceType.ICMP,
          ServiceType.TLS_CERT);

  private static final String INTERNAL_ERROR = "Erreur interne";

  private final Map<ServiceType, Probe> probes = new EnumMap<>(ServiceType.class);
  private final ProbeTargetGuard targets;
  private final MonitoredServiceRepository services;
  private final MeasureIngestionService ingestion;
  private final TransactionTemplate transaction;
  private final Clock clock;
  private final JobGuard guard;
  private final ExecutorService executor;

  public ProbeService(
      List<Probe> probes,
      ProbeTargetGuard targets,
      MonitoredServiceRepository services,
      MeasureIngestionService ingestion,
      TransactionTemplate transaction,
      Clock clock,
      JobGuard guard,
      @Value("${collection.probe.parallelism:16}") int parallelism) {
    for (Probe probe : probes) {
      if (this.probes.putIfAbsent(probe.type(), probe) != null) {
        throw new IllegalStateException("Deux sondes pour le type " + probe.type());
      }
    }
    this.targets = targets;
    this.services = services;
    this.ingestion = ingestion;
    this.transaction = transaction;
    this.clock = clock;
    this.guard = guard;
    // parallelism <= 1 : tout se passe dans le thread appelant (tests, petits parcs).
    this.executor = parallelism > 1 ? Executors.newFixedThreadPool(parallelism) : null;
  }

  @PreDestroy
  void shutdown() {
    if (executor != null) {
      executor.shutdown();
    }
  }

  /** Passage planifié : une seule instance exécute les sondes échues à la fois. */
  @Scheduled(fixedDelayString = "${collection.probe.scan-interval-ms:15000}")
  public void scheduledScan() {
    guard.run("collection.probe-scan", Duration.ofMinutes(5), this::runDue);
  }

  /** Exécute les sondes dont l'échéance est atteinte ; retourne le nombre de sondes exécutées. */
  public int runDue() {
    List<Plan> due = transaction.execute(status -> claimDue());
    if (due == null || due.isEmpty()) {
      return 0;
    }
    if (executor == null) {
      due.forEach(this::runAndStoreSafely);
    } else {
      var futures =
          due.stream()
              .map(plan -> CompletableFuture.runAsync(() -> runAndStoreSafely(plan), executor))
              .toList();
      futures.forEach(CompletableFuture::join);
    }
    return due.size();
  }

  /**
   * Teste un service tout de suite (bouton « Tester ») : retourne le résultat sans stocker de
   * mesure, sans alerte et sans toucher à l'échéance ni au dernier résultat.
   */
  public ProbeResult runOnce(MonitoredService service) {
    if (!PROBE_TYPES.contains(service.getType())) {
      return ProbeResult.down("Type de service non sondé");
    }
    return execute(plan(service));
  }

  /**
   * Retient les services échus et fixe leur prochaine échéance avant toute exécution : un passage
   * concurrent ne les reprend pas, et une longue panne donne une seule exécution.
   */
  private List<Plan> claimDue() {
    Instant now = clock.instant();
    return services.findByActiveTrueAndNextRunAtLessThanEqual(now).stream()
        .filter(s -> PROBE_TYPES.contains(s.getType()))
        .map(
            s -> {
              s.setNextRunAt(now.plusSeconds(Math.max(1, s.getIntervalSeconds())));
              services.save(s);
              return s.getEquipment().isMonitoringEnabled() ? plan(s) : null;
            })
        .filter(Objects::nonNull)
        .toList();
  }

  private void runAndStoreSafely(Plan plan) {
    try {
      ProbeResult result = execute(plan);
      Instant at = clock.instant();
      transaction.executeWithoutResult(status -> store(plan.serviceId(), result, at));
    } catch (RuntimeException e) {
      // Ne jamais laisser une sonde défaillante arrêter la boucle des autres.
      LOG.error(
          "Enregistrement de la sonde {} en erreur : {}", plan.serviceId(), e.getClass().getName());
    }
  }

  /** Dépose le résultat seulement si le service est encore actif et supervisé à cet instant. */
  private void store(UUID serviceId, ProbeResult result, Instant at) {
    MonitoredService service = services.findById(serviceId).orElse(null);
    if (service == null
        || !service.isActive()
        || !PROBE_TYPES.contains(service.getType())
        || !service.getEquipment().isMonitoringEnabled()) {
      return;
    }
    ingestion.ingestProbe(serviceId, result, at);
  }

  /** Exécute la sonde ; toute erreur devient un résultat en échec, jamais une exception. */
  private ProbeResult execute(Plan plan) {
    try {
      Probe probe = probes.get(plan.type());
      if (probe == null) {
        return ProbeResult.down("Sonde indisponible");
      }
      if (plan.port() == null && plan.type() == ServiceType.TCP_PORT) {
        return ProbeResult.down("Port manquant");
      }
      InetAddress address = targets.resolve(plan.host());
      var request =
          new ProbeRequest(
              address,
              plan.host(),
              plan.port(),
              plan.path(),
              plan.timeout(),
              plan.expectedStatus(),
              plan.expectedText());
      ProbeResult result = probe.run(request);
      return result == null ? ProbeResult.down(INTERNAL_ERROR) : result;
    } catch (ProbeTargetException e) {
      return ProbeResult.down(e.getMessage());
    } catch (RuntimeException e) {
      LOG.warn("Sonde {} en erreur interne : {}", plan.serviceId(), e.getClass().getName());
      return ProbeResult.down(INTERNAL_ERROR);
    }
  }

  /**
   * Prépare la cible à partir du service (lu dans une transaction) : hôte, port, chemin et délai
   * borné. Une adresse illisible donne un plan dont l'hôte sera refusé par la garde.
   */
  private Plan plan(MonitoredService service) {
    ServiceType type = service.getType();
    String accessPoint = service.getAccessPoint() == null ? "" : service.getAccessPoint().trim();
    String host;
    Integer urlPort = null;
    String path = null;
    if (accessPoint.isEmpty()) {
      host = service.getEquipment().getManagementAddress();
    } else if (isWeb(type) || accessPoint.contains("://")) {
      Target parsed = parseUrl(accessPoint);
      host = parsed.host();
      urlPort = parsed.port();
      path = parsed.path();
    } else {
      host = accessPoint;
    }
    if (isWeb(type) && (path == null || path.isEmpty())) {
      path = "/";
    }
    Integer port = service.getPort() != null ? service.getPort() : urlPort;
    if (port == null) {
      port = defaultPort(type);
    }
    long timeoutMs = Math.max(1, Math.min(service.getTimeoutMs(), 30_000));
    return new Plan(
        service.getId(),
        type,
        host,
        port,
        path,
        Duration.ofMillis(timeoutMs),
        service.getExpectedStatus(),
        service.getExpectedText());
  }

  private static boolean isWeb(ServiceType type) {
    return type == ServiceType.HTTP || type == ServiceType.HTTPS || type == ServiceType.TLS_CERT;
  }

  private static Integer defaultPort(ServiceType type) {
    return switch (type) {
      case HTTP -> 80;
      case HTTPS, TLS_CERT -> 443;
      default -> null;
    };
  }

  /** Lit « https://hote:port/chemin », ou « hote/chemin » sans schéma. */
  private static Target parseUrl(String value) {
    try {
      URI uri = new URI(value.contains("://") ? value : "//" + value);
      String host = uri.getHost();
      if (host == null) {
        return new Target("", null, null);
      }
      if (host.startsWith("[") && host.endsWith("]")) {
        host = host.substring(1, host.length() - 1);
      }
      String path = uri.getRawPath();
      if (uri.getRawQuery() != null) {
        path = (path == null ? "" : path) + "?" + uri.getRawQuery();
      }
      return new Target(host, uri.getPort() == -1 ? null : uri.getPort(), path);
    } catch (URISyntaxException e) {
      // Hôte vide : la garde le refuse avec un message court, stocké comme échec de sonde.
      return new Target("", null, null);
    }
  }

  private record Target(String host, Integer port, String path) {}

  /** Cible figée d'une exécution, lue en transaction puis utilisée hors transaction. */
  private record Plan(
      UUID serviceId,
      ServiceType type,
      String host,
      Integer port,
      String path,
      Duration timeout,
      String expectedStatus,
      String expectedText) {}
}

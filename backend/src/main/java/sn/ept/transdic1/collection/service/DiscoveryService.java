package sn.ept.transdic1.collection.service;

import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.security.concurrent.DelegatingSecurityContextRunnable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import sn.ept.transdic1.audit.service.AuditService;
import sn.ept.transdic1.collection.dto.DiscoveryDtos.DiscoveryRequest;
import sn.ept.transdic1.collection.dto.DiscoveryDtos.DiscoveryScanResponse;
import sn.ept.transdic1.collection.dto.DiscoveryDtos.DiscoverySettingsResponse;
import sn.ept.transdic1.collection.repository.DiscoveryRepository;
import sn.ept.transdic1.collection.service.SnmpClient.Reading;
import sn.ept.transdic1.collection.service.SnmpClient.SnmpFailure;
import sn.ept.transdic1.collection.service.SnmpClient.Target;
import sn.ept.transdic1.common.secret.SecretResolver;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.EquipmentType;
import sn.ept.transdic1.inventory.domain.SnmpVersion;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;

/**
 * Découverte automatique (FEAT-07) : interroge en SNMP chaque adresse d'une plage, uniquement si
 * elle est incluse dans une plage autorisée par l'exploitation (DISCOVERY_ALLOWED_RANGES). Les
 * équipements qui répondent sont proposés ; rien n'est créé dans l'inventaire. Débit et durée sont
 * bornés, une seule recherche tourne à la fois, chaque recherche est tracée dans l'audit.
 */
@Service
public class DiscoveryService {
  private static final Logger LOG = LoggerFactory.getLogger(DiscoveryService.class);
  static final String SYS_DESCR = "1.3.6.1.2.1.1.1.0";
  static final String SYS_OBJECT_ID = "1.3.6.1.2.1.1.2.0";
  static final String SYS_NAME = "1.3.6.1.2.1.1.5.0";
  private static final String AUDIT_TARGET = "DISCOVERY";

  private final DiscoveryRepository repository;
  private final EquipmentRepository equipment;
  private final SnmpClient snmp;
  private final SecretResolver secrets;
  private final AuditService audit;
  private final Clock clock;
  private final List<Ipv4Range> allowed;
  private final int maxHosts;
  private final int concurrency;
  private final Duration hostTimeout;
  private final Duration maxDuration;
  private final int port;
  private final ExecutorService scans = Executors.newSingleThreadExecutor();

  public DiscoveryService(
      DiscoveryRepository repository,
      EquipmentRepository equipment,
      SnmpClient snmp,
      SecretResolver secrets,
      AuditService audit,
      Clock clock,
      @Value("${discovery.allowed-ranges:}") List<String> allowedRanges,
      @Value("${discovery.max-hosts:256}") int maxHosts,
      @Value("${discovery.concurrency:8}") int concurrency,
      @Value("${discovery.host-timeout-ms:1000}") long hostTimeoutMs,
      @Value("${discovery.max-duration-seconds:300}") long maxDurationSeconds,
      @Value("${collection.snmp.default-port:161}") int port) {
    this.repository = repository;
    this.equipment = equipment;
    this.snmp = snmp;
    this.secrets = secrets;
    this.audit = audit;
    this.clock = clock;
    // Une plage mal écrite dans la configuration empêche le démarrage : mieux vaut une erreur
    // claire qu'une découverte silencieusement restreinte ou élargie.
    this.allowed =
        allowedRanges.stream()
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .map(Ipv4Range::parse)
            .toList();
    this.maxHosts = maxHosts;
    this.concurrency = Math.max(1, concurrency);
    this.hostTimeout = Duration.ofMillis(hostTimeoutMs);
    this.maxDuration = Duration.ofSeconds(maxDurationSeconds);
    this.port = port;
  }

  public DiscoverySettingsResponse settings() {
    return new DiscoverySettingsResponse(
        allowed.stream().map(Ipv4Range::toString).toList(),
        maxHosts,
        (int) maxDuration.toSeconds());
  }

  public List<DiscoveryScanResponse> recent() {
    return repository.recent(20);
  }

  public DiscoveryScanResponse get(UUID id) {
    return repository
        .find(id)
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Recherche introuvable"));
  }

  /** Vérifie la demande, l'enregistre et lance la recherche en arrière-plan. */
  public DiscoveryScanResponse start(DiscoveryRequest request, String username) {
    Ipv4Range range;
    try {
      range = Ipv4Range.parse(request.cidr());
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
    if (allowed.stream().noneMatch(range::within)) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN,
          "Plage non autorisée : seules les plages configurées par l'exploitation peuvent être"
              + " parcourues");
    }
    if (range.hostCount() > maxHosts) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Plage trop grande : " + maxHosts + " adresses au maximum");
    }
    Target template = target(request, "0.0.0.0");
    if (repository.anyRunning()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Une recherche est déjà en cours");
    }
    UUID id = UUID.randomUUID();
    List<String> hosts = range.hosts();
    try {
      repository.insertScan(
          id,
          range.toString(),
          request.snmpVersion(),
          request.credentialReference(),
          request.securityIdentity(),
          username,
          hosts.size(),
          clock.instant());
    } catch (org.springframework.dao.DataIntegrityViolationException e) {
      // Deux demandes simultanées : l'index unique sur les recherches en cours tranche.
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Une recherche est déjà en cours");
    }
    audit.record(
        "DISCOVERY_STARTED",
        AUDIT_TARGET,
        id,
        "plage=" + range + " version=" + request.snmpVersion() + " hotes=" + hosts.size());
    // Le contexte de sécurité suit la tâche : l'audit de fin porte le même auteur.
    scans.submit(
        DelegatingSecurityContextRunnable.create(
            () -> run(id, hosts, template), SecurityContextHolder.getContext()));
    return get(id);
  }

  void run(UUID id, List<String> hosts, Target template) {
    Instant deadline = clock.instant().plus(maxDuration);
    AtomicInteger scanned = new AtomicInteger();
    AtomicInteger found = new AtomicInteger();
    ExecutorService workers = Executors.newFixedThreadPool(concurrency);
    String error = null;
    String status = "COMPLETED";
    try {
      List<Future<?>> tasks = new ArrayList<>();
      for (String host : hosts) {
        tasks.add(
            workers.submit(
                () -> {
                  if (clock.instant().isAfter(deadline)) {
                    return;
                  }
                  if (probe(id, host, template)) {
                    found.incrementAndGet();
                  }
                  int done = scanned.incrementAndGet();
                  if (done % 16 == 0) {
                    repository.progress(id, done);
                  }
                }));
      }
      for (Future<?> task : tasks) {
        task.get();
      }
      if (scanned.get() < hosts.size()) {
        error = "Durée maximale atteinte : recherche partielle";
      }
    } catch (Exception e) {
      status = "FAILED";
      error = "Recherche interrompue : " + e.getClass().getSimpleName();
      LOG.warn("Découverte {} en échec : {}", id, e.getClass().getSimpleName());
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
    } finally {
      workers.shutdownNow();
    }
    repository.finish(id, status, scanned.get(), error, clock.instant());
    audit.record(
        "DISCOVERY_FINISHED",
        AUDIT_TARGET,
        id,
        "statut=" + status + " interrogees=" + scanned.get() + " trouves=" + found.get());
  }

  /** Interroge une adresse ; vrai si elle a répondu. Un silence est normal et non journalisé. */
  private boolean probe(UUID scanId, String host, Target template) {
    Target target =
        new Target(
            host,
            template.port(),
            template.version(),
            template.community(),
            template.username(),
            template.authPassphrase(),
            template.privPassphrase(),
            hostTimeout,
            0);
    List<Reading> readings;
    try {
      readings = snmp.get(target, List.of(SYS_DESCR, SYS_OBJECT_ID, SYS_NAME));
    } catch (SnmpFailure e) {
      return false;
    }
    String descr = text(readings, SYS_DESCR);
    String name = text(readings, SYS_NAME);
    if (descr == null && name == null) {
      return false;
    }
    UUID existing = equipment.findFirstByManagementAddress(host).map(Equipment::getId).orElse(null);
    repository.insertCandidate(
        scanId,
        host,
        truncate(name, 255),
        truncate(descr, 512),
        truncate(text(readings, SYS_OBJECT_ID), 128),
        suggestType(descr),
        existing,
        clock.instant());
    return true;
  }

  private Target target(DiscoveryRequest request, String host) {
    if (!SecretResolver.isSupported(request.credentialReference())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Le secret doit être une référence de la forme env:NOM");
    }
    String secret;
    try {
      secret = secrets.resolve(request.credentialReference());
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
    if (request.snmpVersion() == SnmpVersion.V3) {
      if (request.securityIdentity() == null || request.securityIdentity().isBlank()) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "L'identité SNMPv3 est obligatoire");
      }
      String[] parts = secret.split("\\|", 2);
      return new Target(
          host,
          port,
          SnmpVersion.V3,
          null,
          request.securityIdentity(),
          parts[0],
          parts.length > 1 && !parts[1].isBlank() ? parts[1] : null,
          hostTimeout,
          0);
    }
    return new Target(host, port, SnmpVersion.V2C, secret, null, null, null, hostTimeout, 0);
  }

  /** Type probable d'après sysDescr ; l'utilisateur le confirme avant tout import. */
  static EquipmentType suggestType(String descr) {
    String d = descr == null ? "" : descr.toLowerCase(Locale.ROOT);
    if (d.contains("access point") || d.contains("wireless") || d.contains("aironet")) {
      return EquipmentType.WIFI_ACCESS_POINT;
    }
    if (d.contains("switch") || d.contains("catalyst") || d.contains("procurve")) {
      return EquipmentType.SWITCH;
    }
    if (d.contains("router") || d.contains("routeros") || d.contains("cisco ios")) {
      return EquipmentType.ROUTER;
    }
    if (d.contains("vmware") || d.contains("virtual") || d.contains("kvm")) {
      return EquipmentType.VIRTUAL_MACHINE;
    }
    return EquipmentType.SERVER;
  }

  private static String text(List<Reading> readings, String oid) {
    return readings.stream()
        .filter(r -> oid.equals(r.oid()) || ("." + oid).equals(r.oid()))
        .map(
            r ->
                r.text() != null
                    ? r.text()
                    : r.number() != null ? r.number().toPlainString() : null)
        .filter(v -> v != null && !v.isBlank())
        .findFirst()
        .orElse(null);
  }

  private static String truncate(String value, int max) {
    return value == null || value.length() <= max ? value : value.substring(0, max);
  }

  /** Une recherche « en cours » au démarrage a été coupée par l'arrêt du serveur. */
  @EventListener(ApplicationReadyEvent.class)
  public void interruptStaleScans() {
    int stale = repository.interruptRunning(clock.instant());
    if (stale > 0) {
      LOG.warn("{} recherche(s) de découverte interrompue(s) par un arrêt du serveur", stale);
    }
  }

  @PreDestroy
  void shutdown() {
    scans.shutdownNow();
  }
}

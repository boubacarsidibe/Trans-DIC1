package sn.ept.transdic1.collection.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import sn.ept.transdic1.alerting.service.CollectionAlertService;
import sn.ept.transdic1.audit.service.AuditService;
import sn.ept.transdic1.collection.dto.HypervisorDtos.SyncReport;
import sn.ept.transdic1.collection.dto.HypervisorDtos.TestResult;
import sn.ept.transdic1.collection.dto.MeasureInput;
import sn.ept.transdic1.collection.port.HypervisorEndpoint;
import sn.ept.transdic1.collection.port.HypervisorInventory;
import sn.ept.transdic1.collection.port.HypervisorSource;
import sn.ept.transdic1.collection.port.HypervisorSourceException;
import sn.ept.transdic1.collection.port.HypervisorSourceException.Reason;
import sn.ept.transdic1.collection.probe.ProbeTargetException;
import sn.ept.transdic1.collection.probe.ProbeTargetGuard;
import sn.ept.transdic1.collection.repository.HypervisorLinkRepository;
import sn.ept.transdic1.common.scheduling.JobGuard;
import sn.ept.transdic1.common.secret.SecretResolver;
import sn.ept.transdic1.inventory.domain.AgentStatus;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.EquipmentStatus;
import sn.ept.transdic1.inventory.domain.EquipmentType;
import sn.ept.transdic1.inventory.domain.HypervisorConnector;
import sn.ept.transdic1.inventory.domain.HypervisorKind;
import sn.ept.transdic1.inventory.domain.HypervisorResource;
import sn.ept.transdic1.inventory.domain.HypervisorResourceKind;
import sn.ept.transdic1.inventory.domain.HypervisorResourceStatus;
import sn.ept.transdic1.inventory.domain.MonitoredService;
import sn.ept.transdic1.inventory.domain.PowerState;
import sn.ept.transdic1.inventory.domain.ServiceType;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.repository.CollectionAgentRepository;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.HypervisorConnectorRepository;
import sn.ept.transdic1.inventory.repository.HypervisorResourceRepository;
import sn.ept.transdic1.inventory.repository.MonitoredServiceRepository;

/**
 * Collecte sans agent auprès des hyperviseurs (ADR 0055) : à chaque passage, un connecteur lit ses
 * hôtes et ses invités, et la plateforme en tire l'inventaire, la topologie, les mesures et les
 * alertes — sans rien installer dans les VM.
 *
 * <ul>
 *   <li>Un équipement par hôte (serveur) et par invité (machine virtuelle), créé sans doublon
 *       d'après l'identifiant stable de l'hyperviseur ; un équipement déjà inventorié à la même
 *       adresse dans le site est repris plutôt que doublé.
 *   <li>Le lien VM → hôte de la topologie existante, déplacé quand la VM migre : le regroupement
 *       des alertes (ADR 0014, 0039) range ainsi les alertes des VM sous celle de leur hôte.
 *   <li>Les mesures ({@code hv.*}) passent par le circuit d'ingestion commun ; une VM éteinte après
 *       avoir tourné, ou dont l'hôte est hors ligne, ouvre une alerte de collecte.
 *   <li>Une ressource disparue de l'hyperviseur est marquée, jamais supprimée : son historique
 *       reste.
 * </ul>
 *
 * Chaque invité est traité dans sa propre transaction : une VM qui pose problème n'annule pas le
 * passage des autres. Les appels réseau à l'hyperviseur se font hors transaction.
 */
@Service
public class HypervisorCollectionService {
  private static final Logger LOG = LoggerFactory.getLogger(HypervisorCollectionService.class);
  private static final SecureRandom RANDOM = new SecureRandom();
  static final String PLACEHOLDER_PREFIX = "hv-";
  static final String KEY_POWER = "hv.power";

  private final List<HypervisorSource> sources;
  private final HypervisorConnectorRepository connectors;
  private final HypervisorResourceRepository resources;
  private final EquipmentRepository equipments;
  private final CollectionAgentRepository agents;
  private final MonitoredServiceRepository services;
  private final HypervisorLinkRepository links;
  private final MeasureIngestionService ingestion;
  private final CollectionAlertService collectionAlerts;
  private final AuditService audit;
  private final SecretResolver secrets;
  private final ProbeTargetGuard guard;
  private final JobGuard jobs;
  private final TransactionTemplate transaction;
  private final Clock clock;
  private final Duration timeout;
  private final int failureThreshold;
  private final int addressLookupsPerRun;
  private final Map<String, long[]> counters = new ConcurrentHashMap<>();

  public HypervisorCollectionService(
      List<HypervisorSource> sources,
      HypervisorConnectorRepository connectors,
      HypervisorResourceRepository resources,
      EquipmentRepository equipments,
      CollectionAgentRepository agents,
      MonitoredServiceRepository services,
      HypervisorLinkRepository links,
      MeasureIngestionService ingestion,
      CollectionAlertService collectionAlerts,
      AuditService audit,
      SecretResolver secrets,
      ProbeTargetGuard guard,
      JobGuard jobs,
      TransactionTemplate transaction,
      Clock clock,
      @Value("${collection.hypervisor.timeout-seconds:15}") long timeoutSeconds,
      @Value("${collection.hypervisor.failure-threshold:3}") int failureThreshold,
      @Value("${collection.hypervisor.address-lookups-per-run:50}") int addressLookupsPerRun) {
    this.sources = sources;
    this.connectors = connectors;
    this.resources = resources;
    this.equipments = equipments;
    this.agents = agents;
    this.services = services;
    this.links = links;
    this.ingestion = ingestion;
    this.collectionAlerts = collectionAlerts;
    this.audit = audit;
    this.secrets = secrets;
    this.guard = guard;
    this.jobs = jobs;
    this.transaction = transaction;
    this.clock = clock;
    this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
    this.failureThreshold = Math.max(1, failureThreshold);
    this.addressLookupsPerRun = Math.max(0, addressLookupsPerRun);
  }

  /** Implémentation pour cette famille d'hyperviseur, si elle existe. */
  public Optional<HypervisorSource> source(HypervisorKind kind) {
    return sources.stream().filter(s -> s.kind() == kind).findFirst();
  }

  // --- Passage planifié --------------------------------------------------------------------------

  /** Toutes les 30 s : les connecteurs dont l'intervalle est échu, une instance à la fois. */
  @Scheduled(fixedDelayString = "${collection.hypervisor.tick-ms:30000}")
  public void scheduledRun() {
    jobs.run(
        "collection.hypervisor",
        Duration.ofMinutes(15),
        () -> {
          Instant now = clock.instant();
          List<UUID> due =
              transaction.execute(
                  s ->
                      connectors.findByEnabledTrue().stream()
                          .filter(c -> c.isDue(now))
                          .map(HypervisorConnector::getId)
                          .toList());
          for (UUID id : due == null ? List.<UUID>of() : due) {
            synchronize(id);
          }
          return due == null ? 0 : due.size();
        });
  }

  // --- Synchronisation d'un connecteur --------------------------------------------------------

  /** Configuration figée d'un connecteur, lue en transaction puis utilisée hors transaction. */
  record Settings(
      UUID id,
      String name,
      HypervisorKind kind,
      String apiUrl,
      String tokenId,
      String secretReference,
      String caCertificate,
      UUID siteId,
      boolean createIcmpProbes) {
    static Settings of(HypervisorConnector c) {
      return new Settings(
          c.getId(),
          c.getName(),
          c.getKind(),
          c.getApiUrl(),
          c.getTokenId(),
          c.getSecretReference(),
          c.getCaCertificate(),
          c.getSite().getId(),
          c.isCreateIcmpProbes());
    }
  }

  /** Ce que le passage a fait, pour le bilan. */
  static final class Tally {
    int created;
    int migrations;
    int removed;
    int alerts;
    int lookups;
  }

  /** Ce que l'on sait déjà d'une ressource présente, lu avant les appels réseau. */
  record Known(UUID id, String name, String smbiosUuid, String ipAddress) {}

  /**
   * Un passage complet ; toujours un bilan, jamais d'exception pour une erreur de l'hyperviseur.
   */
  public SyncReport synchronize(UUID connectorId) {
    Settings settings =
        transaction.execute(s -> connectors.findById(connectorId).map(Settings::of).orElse(null));
    if (settings == null) {
      return new SyncReport(false, 0, 0, 0, 0, 0, 0, "Connecteur introuvable");
    }
    HypervisorSource source;
    HypervisorEndpoint endpoint;
    HypervisorInventory inventory;
    try {
      source = available(settings.kind());
      endpoint = endpoint(settings);
      inventory = source.read(endpoint);
    } catch (HypervisorSourceException e) {
      int opened = recordFailure(connectorId, e.getMessage(), e.meansUnreachable());
      LOG.warn("Connecteur d'hyperviseur {} en échec : {}", settings.name(), e.reason());
      return new SyncReport(false, 0, 0, 0, 0, 0, opened, e.getMessage());
    }

    Tally tally = new Tally();
    Map<String, Known> known =
        transaction.execute(
            s -> {
              Map<String, Known> map = new HashMap<>();
              for (HypervisorResource r :
                  resources.findByConnectorAndStatus(
                      connectorId, HypervisorResourceStatus.PRESENT)) {
                map.put(
                    r.getExternalId(),
                    new Known(r.getId(), r.getName(), r.getSmbiosUuid(), r.getIpAddress()));
              }
              return map;
            });
    Set<String> seen = new HashSet<>();
    Map<String, UUID> hostEquipments = new HashMap<>();
    Map<String, String> hostNames = new HashMap<>();
    Instant now = clock.instant();

    for (HypervisorInventory.Host host : inventory.hosts()) {
      seen.add(host.externalId());
      hostNames.put(host.externalId(), host.name());
      try {
        UUID equipmentId = transaction.execute(s -> applyHost(settings, host, now, tally));
        hostEquipments.put(host.externalId(), equipmentId);
      } catch (RuntimeException e) {
        LOG.warn("Hôte {} non traité : {}", host.name(), e.getClass().getSimpleName());
      }
    }
    for (HypervisorInventory.Guest guest : inventory.guests()) {
      if (guest.template()) {
        continue;
      }
      seen.add(guest.externalId());
      try {
        Known previous = known.get(guest.externalId());
        String uuid = lookUpUuid(source, endpoint, guest, previous);
        List<String> addresses = lookUpAddresses(source, endpoint, guest, previous, tally);
        transaction.executeWithoutResult(
            s ->
                applyGuest(
                    settings,
                    guest,
                    previous,
                    uuid,
                    addresses,
                    hostEquipments,
                    hostNames,
                    now,
                    tally));
      } catch (HypervisorSourceException e) {
        LOG.warn("Invité {} non traité : {}", guest.name(), e.reason());
      } catch (RuntimeException e) {
        LOG.warn("Invité {} non traité : {}", guest.name(), e.getClass().getSimpleName());
      }
    }
    transaction.executeWithoutResult(s -> finish(connectorId, seen, now, tally));
    return new SyncReport(
        true,
        inventory.hosts().size(),
        (int) inventory.guests().stream().filter(g -> !g.template()).count(),
        tally.created,
        tally.migrations,
        tally.removed,
        tally.alerts,
        null);
  }

  /** Essai de connexion : lit l'inventaire sans rien écrire. */
  public TestResult test(UUID connectorId) {
    Settings settings =
        transaction.execute(s -> connectors.findById(connectorId).map(Settings::of).orElse(null));
    if (settings == null) {
      return new TestResult(false, "Connecteur introuvable", 0, 0);
    }
    try {
      HypervisorInventory inventory = available(settings.kind()).read(endpoint(settings));
      long guests = inventory.guests().stream().filter(g -> !g.template()).count();
      return new TestResult(
          true,
          "Connexion réussie : " + inventory.hosts().size() + " hôte(s), " + guests + " invité(s)",
          inventory.hosts().size(),
          (int) guests);
    } catch (HypervisorSourceException e) {
      return new TestResult(false, e.getMessage(), 0, 0);
    }
  }

  private HypervisorSource available(HypervisorKind kind) throws HypervisorSourceException {
    return source(kind)
        .filter(HypervisorSource::available)
        .orElseThrow(
            () ->
                new HypervisorSourceException(
                    Reason.NOT_AVAILABLE, "Connecteur " + kind + " pas encore disponible"));
  }

  /**
   * Adresse validée par la garde anti-SSRF et secret résolu au dernier moment. Le message d'erreur
   * cite la référence du secret, jamais sa valeur.
   */
  HypervisorEndpoint endpoint(Settings settings) throws HypervisorSourceException {
    URI uri = URI.create(settings.apiUrl());
    try {
      guard.resolveAll(uri.getHost());
    } catch (ProbeTargetException e) {
      throw new HypervisorSourceException(Reason.UNREACHABLE, e.getMessage());
    }
    String secret;
    try {
      secret = secrets.resolve(settings.secretReference());
    } catch (IllegalStateException e) {
      throw new HypervisorSourceException(Reason.AUTH_REFUSED, e.getMessage());
    }
    return new HypervisorEndpoint(
        uri, settings.tokenId(), secret, settings.caCertificate(), timeout);
  }

  private String lookUpUuid(
      HypervisorSource source,
      HypervisorEndpoint endpoint,
      HypervisorInventory.Guest guest,
      Known previous)
      throws HypervisorSourceException {
    if (guest.kind() != HypervisorResourceKind.VM) {
      return null;
    }
    // Une VM connue n'est relue que si son UUID manque ou si son nom a changé (une VM recréée
    // sous le même numéro porte presque toujours un autre nom) : un appel par VM nouvelle.
    if (previous != null
        && previous.smbiosUuid() != null
        && previous.name() != null
        && previous.name().equals(truncate(guest.name(), 120))) {
      return previous.smbiosUuid();
    }
    return source.guestUuid(endpoint, guest).orElse(null);
  }

  private List<String> lookUpAddresses(
      HypervisorSource source,
      HypervisorEndpoint endpoint,
      HypervisorInventory.Guest guest,
      Known previous,
      Tally tally)
      throws HypervisorSourceException {
    if (guest.power() != PowerState.RUNNING) {
      return List.of();
    }
    if (previous != null && previous.ipAddress() != null) {
      return List.of(previous.ipAddress());
    }
    // Borné par passage : un cluster de milliers de VM ne fait pas des milliers d'appels d'un coup.
    if (tally.lookups >= addressLookupsPerRun) {
      return List.of();
    }
    tally.lookups++;
    return source.guestAddresses(endpoint, guest);
  }

  // --- Hôtes ------------------------------------------------------------------------------------

  private UUID applyHost(
      Settings settings, HypervisorInventory.Host host, Instant now, Tally tally) {
    var resource =
        presentResource(settings.id(), host.externalId())
            .orElseGet(
                () ->
                    new HypervisorResource(
                        connectors.getReferenceById(settings.id()),
                        host.externalId(),
                        HypervisorResourceKind.HOST,
                        truncate(host.name(), 120),
                        now));
    Equipment equipment = resource.getEquipment();
    if (equipment == null) {
      equipment =
          adoptOrCreate(
              settings,
              host.externalId(),
              host.name(),
              host.address(),
              EquipmentType.SERVER,
              "Hôte d'hyperviseur " + settings.kind().name().toLowerCase(Locale.ROOT),
              tally);
      resource.attach(equipment);
    }
    resource.observe(truncate(host.name(), 120), null, host.power(), now);
    resource.setIpAddress(host.address());
    resources.save(resource);
    replacePlaceholder(equipment, host.address());

    if (!equipment.isMonitoringEnabled()) {
      return equipment.getId();
    }
    if (host.power() == PowerState.ONLINE) {
      ingestion.ingestObserved(equipment.getId(), hostMeasures(host, now), true);
    } else {
      ingestion.ingestObserved(
          equipment.getId(), List.of(measure(KEY_POWER, 0, "state", now)), false);
      if (!hasActiveAgent(equipment)) {
        String message =
            "Hôte " + host.name() + " hors ligne selon l'hyperviseur (" + settings.name() + ")";
        if (collectionAlerts.reportFailure(equipment, message, now).isPresent()) {
          tally.alerts++;
        }
      }
    }
    return equipment.getId();
  }

  // --- Invités ----------------------------------------------------------------------------------

  private void applyGuest(
      Settings settings,
      HypervisorInventory.Guest guest,
      Known previous,
      String uuid,
      List<String> addresses,
      Map<String, UUID> hostEquipments,
      Map<String, String> hostNames,
      Instant now,
      Tally tally) {
    HypervisorResource resource =
        previous == null ? null : resources.findById(previous.id()).orElse(null);
    // Même numéro, autre UUID matériel : ce n'est pas la même VM. L'ancienne est marquée
    // disparue (son équipement garde son historique), la nouvelle aura son propre équipement.
    if (resource != null
        && uuid != null
        && resource.getSmbiosUuid() != null
        && !resource.getSmbiosUuid().equals(uuid)) {
      resource.markRemoved(now);
      resources.saveAndFlush(resource);
      audit.record(
          "HYPERVISOR_RESOURCE_REMOVED",
          "HYPERVISOR_RESOURCE",
          resource.getId(),
          "connecteur="
              + settings.name()
              + " ressource="
              + resource.getExternalId()
              + " remplacee");
      tally.removed++;
      resource = null;
    }
    if (resource == null) {
      resource =
          new HypervisorResource(
              connectors.getReferenceById(settings.id()),
              guest.externalId(),
              guest.kind(),
              truncate(guest.name(), 120),
              now);
    }
    if (uuid != null) {
      resource.setSmbiosUuid(uuid);
    }
    String address = addresses.isEmpty() ? null : addresses.get(0);
    Equipment equipment = resource.getEquipment();
    if (equipment == null) {
      equipment =
          adoptOrCreate(
              settings,
              guest.externalId(),
              guest.name(),
              address,
              EquipmentType.VIRTUAL_MACHINE,
              guest.kind() == HypervisorResourceKind.CONTAINER
                  ? "Conteneur (" + settings.kind().name().toLowerCase(Locale.ROOT) + ")"
                  : "VM (" + settings.kind().name().toLowerCase(Locale.ROOT) + ")",
              tally);
      resource.attach(equipment);
    }
    boolean wasRunning = resource.isEverRunning();
    String movedFrom =
        resource.observe(truncate(guest.name(), 120), guest.hostExternalId(), guest.power(), now);
    if (address != null) {
      resource.setIpAddress(address);
    }
    resources.save(resource);
    linkToHost(settings, guest, equipment, hostEquipments, movedFrom, tally);
    if (address != null) {
      replacePlaceholder(equipment, address);
      if (settings.createIcmpProbes()) {
        createInactiveIcmpProbe(equipment, address);
      }
    }

    if (!equipment.isMonitoringEnabled()) {
      return;
    }
    String hostName = hostNames.getOrDefault(guest.hostExternalId(), guest.hostExternalId());
    switch (guest.power()) {
      case RUNNING ->
          ingestion.ingestObserved(
              equipment.getId(), guestMeasures(settings.id(), guest, now), true);
      case STOPPED, PAUSED -> {
        ingestion.ingestObserved(
            equipment.getId(), List.of(measure(KEY_POWER, 0, "state", now)), false);
        // Une VM toujours restée éteinte (modèle, VM de secours) n'ouvre pas d'alerte : seule une
        // VM vue allumée puis éteinte en ouvre une.
        if (wasRunning && !hasActiveAgent(equipment)) {
          report(
              equipment,
              (guest.power() == PowerState.PAUSED
                      ? "VM " + guest.name() + " suspendue"
                      : "VM " + guest.name() + " éteinte")
                  + " (hôte "
                  + hostName
                  + ")",
              now,
              tally);
        }
      }
      default -> {
        if (wasRunning && !hasActiveAgent(equipment)) {
          report(
              equipment,
              "VM " + guest.name() + " injoignable : son hôte " + hostName + " est hors ligne",
              now,
              tally);
        }
      }
    }
  }

  private void report(Equipment equipment, String message, Instant now, Tally tally) {
    if (collectionAlerts.reportFailure(equipment, message, now).isPresent()) {
      tally.alerts++;
    }
  }

  /**
   * Lien VM → hôte : posé s'il manque, déplacé si la VM a migré. Seuls les liens vers les hôtes de
   * ce connecteur sont touchés.
   */
  private void linkToHost(
      Settings settings,
      HypervisorInventory.Guest guest,
      Equipment vm,
      Map<String, UUID> hostEquipments,
      String movedFrom,
      Tally tally) {
    UUID host = hostEquipments.get(guest.hostExternalId());
    if (host == null || host.equals(vm.getId())) {
      return;
    }
    List<UUID> current = links.upstreamsAmong(vm.getId(), new HashSet<>(hostEquipments.values()));
    boolean moved = false;
    for (UUID previous : current) {
      if (!previous.equals(host)) {
        links.unlink(vm.getId(), previous);
        moved = true;
      }
    }
    if (!current.contains(host)) {
      links.link(vm.getId(), host);
    }
    if (moved || movedFrom != null) {
      tally.migrations++;
      audit.record(
          "HYPERVISOR_VM_MIGRATED",
          "EQUIPMENT",
          vm.getId(),
          "connecteur="
              + settings.name()
              + " vm="
              + guest.name()
              + " de="
              + (movedFrom == null ? "?" : movedFrom)
              + " vers="
              + guest.hostExternalId());
    }
  }

  // --- Équipements ------------------------------------------------------------------------------

  /**
   * Équipement d'une ressource nouvelle : un équipement déjà inventorié à la même adresse dans le
   * site, sans autre ressource d'hyperviseur (un serveur physique saisi à la main, une VM inscrite
   * par son agent), sinon un nouvel équipement.
   */
  private Equipment adoptOrCreate(
      Settings settings,
      String externalId,
      String name,
      String address,
      EquipmentType type,
      String system,
      Tally tally) {
    if (address != null) {
      var adoptable =
          equipments
              .findFirstByManagementAddress(address)
              .filter(e -> e.getSite() != null && e.getSite().getId().equals(settings.siteId()))
              .filter(e -> !resources.existsByEquipmentId(e.getId()));
      if (adoptable.isPresent()) {
        audit.record(
            "EQUIPMENT_ADOPTED",
            "EQUIPMENT",
            adoptable.get().getId(),
            "code=" + adoptable.get().getInventoryCode() + " hyperviseur=" + settings.name());
        return adoptable.get();
      }
    }
    Site site = connectors.getReferenceById(settings.id()).getSite();
    String chosen =
        address != null
                && address.length() <= 45
                && equipments.findFirstByManagementAddress(address).isEmpty()
            ? address
            : freePlaceholder(settings.id(), externalId);
    var draft =
        new Equipment(
            inventoryCode(site.getCode()),
            truncate(name, 120),
            chosen,
            type,
            EquipmentStatus.UNKNOWN,
            site);
    draft.setOperatingSystem(truncate(system, 120));
    // Écrit tout de suite : le lien VM → hôte est posé en SQL et référence cet équipement.
    var created = equipments.saveAndFlush(draft);
    tally.created++;
    audit.record(
        "EQUIPMENT_CREATED",
        "EQUIPMENT",
        created.getId(),
        "code="
            + created.getInventoryCode()
            + " type="
            + type
            + " hyperviseur="
            + settings.name()
            + " ressource="
            + externalId);
    return created;
  }

  /** Une adresse provisoire est remplacée par la vraie dès qu'elle est connue et libre. */
  private void replacePlaceholder(Equipment equipment, String address) {
    if (address == null
        || address.length() > 45
        || !equipment.getManagementAddress().startsWith(PLACEHOLDER_PREFIX)
        || equipments.findFirstByManagementAddress(address).isPresent()) {
      return;
    }
    equipment.update(
        equipment.getName(),
        address,
        equipment.getType(),
        equipment.getStatus(),
        equipment.getSite());
  }

  /** Sonde ICMP créée inactive : un administrateur décide de l'activer. */
  private void createInactiveIcmpProbe(Equipment equipment, String address) {
    boolean exists =
        services.findByEquipmentIdOrderByName(equipment.getId()).stream()
            .anyMatch(s -> s.getType() == ServiceType.ICMP);
    if (exists) {
      return;
    }
    var probe =
        new MonitoredService(equipment, "Ping (découvert par l'hyperviseur)", ServiceType.ICMP);
    probe.setAccessPoint(address);
    probe.setActive(false);
    services.save(probe);
    audit.record(
        "PROBE_CREATED",
        "MONITORED_SERVICE",
        probe.getId(),
        "equipement=" + equipment.getId() + " type=ICMP active=false origine=hyperviseur");
  }

  /**
   * Adresse provisoire libre : celle d'une ressource disparue sous le même identifiant peut être
   * encore prise par son équipement (VM recréée sous le même numéro), on la suffixe alors.
   */
  private String freePlaceholder(UUID connectorId, String externalId) {
    String base = placeholder(connectorId, externalId);
    String candidate = base;
    for (int attempt = 0;
        equipments.findFirstByManagementAddress(candidate).isPresent();
        attempt++) {
      if (attempt >= 10) {
        throw new IllegalStateException("Impossible de trouver une adresse provisoire libre");
      }
      byte[] bytes = new byte[2];
      RANDOM.nextBytes(bytes);
      String suffix = "-" + HexFormat.of().formatHex(bytes);
      candidate = base.substring(0, Math.min(base.length(), 45 - suffix.length())) + suffix;
    }
    return candidate;
  }

  /** Adresse provisoire tant que l'adresse réelle de la ressource est inconnue. */
  static String placeholder(UUID connectorId, String externalId) {
    String id = externalId.replaceAll("[^A-Za-z0-9.-]", "-");
    String value = PLACEHOLDER_PREFIX + connectorId.toString().substring(0, 8) + "-" + id;
    return value.length() > 45 ? value.substring(0, 45) : value;
  }

  private String inventoryCode(String siteCode) {
    String prefix = truncate(siteCode == null ? "SITE" : siteCode, 24).toUpperCase(Locale.ROOT);
    for (int attempt = 0; attempt < 10; attempt++) {
      byte[] bytes = new byte[4];
      RANDOM.nextBytes(bytes);
      String code = prefix + "-HV-" + HexFormat.of().withUpperCase().formatHex(bytes);
      if (!equipments.existsByInventoryCode(code)) {
        return code;
      }
    }
    throw new IllegalStateException("Impossible de générer un code d'inventaire unique");
  }

  private boolean hasActiveAgent(Equipment equipment) {
    return agents.findByEquipmentIdAndStatus(equipment.getId(), AgentStatus.ACTIVE).isPresent();
  }

  private Optional<HypervisorResource> presentResource(UUID connectorId, String externalId) {
    return resources
        .findByConnectorAndStatus(connectorId, HypervisorResourceStatus.PRESENT)
        .stream()
        .filter(r -> r.getExternalId().equals(externalId))
        .findFirst();
  }

  // --- Fin de passage et échecs -----------------------------------------------------------------

  private void finish(UUID connectorId, Set<String> seen, Instant now, Tally tally) {
    var connector = connectors.findById(connectorId).orElse(null);
    if (connector == null) {
      return;
    }
    for (HypervisorResource r :
        resources.findByConnectorAndStatus(connectorId, HypervisorResourceStatus.PRESENT)) {
      if (!seen.contains(r.getExternalId())) {
        r.markRemoved(now);
        tally.removed++;
        audit.record(
            "HYPERVISOR_RESOURCE_REMOVED",
            "HYPERVISOR_RESOURCE",
            r.getId(),
            "connecteur="
                + connector.getName()
                + " ressource="
                + r.getExternalId()
                + " nom="
                + r.getName()
                + (r.getEquipment() == null
                    ? ""
                    : " equipement=" + r.getEquipment().getInventoryCode()));
      }
    }
    connector.succeeded(now);
  }

  /**
   * Un passage en échec. Un hyperviseur injoignable plusieurs fois de suite ouvre une alerte de
   * collecte sur ses hôtes ; une erreur de configuration (jeton, certificat) non : elle ne dit rien
   * de l'état des hôtes, et le connecteur affiche l'erreur.
   */
  int recordFailure(UUID connectorId, String message, boolean unreachable) {
    Instant now = clock.instant();
    Integer opened =
        transaction.execute(
            s -> {
              var connector = connectors.findById(connectorId).orElse(null);
              if (connector == null) {
                return 0;
              }
              connector.failed(message, now);
              if (!unreachable || connector.getConsecutiveFailures() < failureThreshold) {
                return 0;
              }
              int count = 0;
              for (HypervisorResource r :
                  resources.findByConnectorAndStatus(
                      connectorId, HypervisorResourceStatus.PRESENT)) {
                Equipment e = r.getEquipment();
                if (r.getKind() != HypervisorResourceKind.HOST
                    || e == null
                    || !e.isMonitoringEnabled()
                    || hasActiveAgent(e)) {
                  continue;
                }
                String text =
                    "Hyperviseur "
                        + connector.getName()
                        + " injoignable depuis "
                        + connector.getConsecutiveFailures()
                        + " passages : "
                        + message;
                if (collectionAlerts.reportFailure(e, text, now).isPresent()) {
                  count++;
                }
              }
              return count;
            });
    return opened == null ? 0 : opened;
  }

  // --- Mesures ----------------------------------------------------------------------------------

  private static List<MeasureInput> hostMeasures(HypervisorInventory.Host host, Instant at) {
    List<MeasureInput> list = new ArrayList<>();
    list.add(measure(KEY_POWER, 1, "state", at));
    add(list, "hv.cpu.usage", host.cpuPercent(), "%", at);
    add(list, "hv.memory.usage", host.memoryPercent(), "%", at);
    add(list, "hv.disk.usage", host.diskPercent(), "%", at);
    add(
        list,
        "hv.uptime",
        host.uptimeSeconds() == null ? null : host.uptimeSeconds().doubleValue(),
        "s",
        at);
    return list;
  }

  private List<MeasureInput> guestMeasures(
      UUID connectorId, HypervisorInventory.Guest guest, Instant at) {
    List<MeasureInput> list = new ArrayList<>();
    list.add(measure(KEY_POWER, 1, "state", at));
    add(list, "hv.cpu.usage", guest.cpuPercent(), "%", at);
    add(list, "hv.memory.usage", guest.memoryPercent(), "%", at);
    add(list, "hv.disk.usage", guest.diskPercent(), "%", at);
    add(
        list,
        "hv.uptime",
        guest.uptimeSeconds() == null ? null : guest.uptimeSeconds().doubleValue(),
        "s",
        at);
    double[] rates =
        rates(connectorId + "/" + guest.externalId(), guest.netInBytes(), guest.netOutBytes(), at);
    if (rates != null) {
      add(list, "hv.net.in", rates[0], "B/s", at);
      add(list, "hv.net.out", rates[1], "B/s", at);
    }
    return list;
  }

  /**
   * Débit réseau d'après deux lectures successives des compteurs cumulés. Un compteur qui recule
   * (redémarrage de la VM) ne donne pas de débit ce passage-ci.
   */
  double[] rates(String key, Long in, Long out, Instant at) {
    if (in == null || out == null) {
      return null;
    }
    long[] previous = counters.put(key, new long[] {in, out, at.getEpochSecond()});
    if (previous == null) {
      return null;
    }
    long seconds = at.getEpochSecond() - previous[2];
    if (seconds <= 0 || in < previous[0] || out < previous[1]) {
      return null;
    }
    return new double[] {
      (in - previous[0]) / (double) seconds, (out - previous[1]) / (double) seconds
    };
  }

  private static void add(
      List<MeasureInput> list, String key, Double value, String unit, Instant at) {
    if (value != null && !value.isNaN() && !value.isInfinite()) {
      list.add(
          new MeasureInput(
              key, BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP), unit, "GOOD", at));
    }
  }

  private static MeasureInput measure(String key, double value, String unit, Instant at) {
    return new MeasureInput(key, BigDecimal.valueOf(value), unit, "GOOD", at);
  }

  private static String truncate(String value, int max) {
    if (value == null) return null;
    return value.length() > max ? value.substring(0, max) : value;
  }
}

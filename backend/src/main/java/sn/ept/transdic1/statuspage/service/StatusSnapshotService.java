package sn.ept.transdic1.statuspage.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import sn.ept.transdic1.statuspage.domain.ComponentSourceType;
import sn.ept.transdic1.statuspage.domain.ComponentState;
import sn.ept.transdic1.statuspage.domain.IncidentImpact;
import sn.ept.transdic1.statuspage.domain.IncidentStatus;
import sn.ept.transdic1.statuspage.domain.StatusComponent;
import sn.ept.transdic1.statuspage.domain.StatusIncident;
import sn.ept.transdic1.statuspage.domain.StatusPage;
import sn.ept.transdic1.statuspage.domain.StatusVisibility;
import sn.ept.transdic1.statuspage.dto.StatusPageDtos.StatusPageView;
import sn.ept.transdic1.statuspage.dto.StatusPageDtos.ViewComponent;
import sn.ept.transdic1.statuspage.dto.StatusPageDtos.ViewIncident;
import sn.ept.transdic1.statuspage.dto.StatusPageDtos.ViewIncidentUpdate;
import sn.ept.transdic1.statuspage.repository.StatusComponentRepository;
import sn.ept.transdic1.statuspage.repository.StatusIncidentRepository;
import sn.ept.transdic1.statuspage.repository.StatusPageRepository;
import sn.ept.transdic1.statuspage.repository.StatusSourceRepository;
import sn.ept.transdic1.statuspage.repository.StatusSourceRepository.EquipmentRow;
import sn.ept.transdic1.statuspage.repository.StatusSourceRepository.HourRow;
import sn.ept.transdic1.statuspage.repository.StatusSourceRepository.MaintenanceRow;
import sn.ept.transdic1.statuspage.repository.StatusSourceRepository.ProbeRow;
import sn.ept.transdic1.statuspage.service.AvailabilityCalculator.Daily;
import sn.ept.transdic1.statuspage.service.AvailabilityCalculator.HourBucket;
import sn.ept.transdic1.statuspage.service.ComponentStateEvaluator.AlertFact;
import sn.ept.transdic1.statuspage.service.ComponentStateEvaluator.EquipmentFacts;
import sn.ept.transdic1.statuspage.service.ComponentStateEvaluator.ProbeFacts;
import sn.ept.transdic1.statuspage.service.ComponentStateEvaluator.Window;

/**
 * Instantané d'une page de statut (ADR 0026) : état courant de chaque composant, disponibilité des
 * 90 derniers jours et incidents récents. L'instantané est gardé {@code statuspage.cache-seconds}
 * (30 s) et la disponibilité {@code statuspage.availability-cache-seconds} (5 min) : un afflux de
 * visites ne déclenche qu'un calcul par période. Toute modification d'une page l'invalide.
 */
@Service
public class StatusSnapshotService {
  public static final int AVAILABILITY_DAYS = 90;
  public static final int INCIDENT_DAYS = 14;
  public static final int MAX_INCIDENTS = 20;
  public static final int MAX_UPDATES = 20;
  private static final String PUBLIC_SLUGS = "public";

  private final StatusPageRepository pages;
  private final StatusComponentRepository components;
  private final StatusIncidentRepository incidents;
  private final StatusSourceRepository sources;
  private final TransactionTemplate readOnly;
  private final Clock clock;
  private final TtlCache<UUID, Snapshot> snapshots;
  private final TtlCache<UUID, Map<UUID, Daily>> availability;
  private final TtlCache<String, Map<String, UUID>> publicSlugs;

  public StatusSnapshotService(
      StatusPageRepository pages,
      StatusComponentRepository components,
      StatusIncidentRepository incidents,
      StatusSourceRepository sources,
      PlatformTransactionManager transactions,
      Clock clock,
      @Value("${statuspage.cache-seconds:30}") long cacheSeconds,
      @Value("${statuspage.availability-cache-seconds:300}") long availabilitySeconds) {
    this.pages = pages;
    this.components = components;
    this.incidents = incidents;
    this.sources = sources;
    this.clock = clock;
    this.readOnly = new TransactionTemplate(transactions);
    readOnly.setReadOnly(true);
    Duration ttl = Duration.ofSeconds(Math.max(1, cacheSeconds));
    snapshots = new TtlCache<>(clock, ttl, 64);
    availability = new TtlCache<>(clock, Duration.ofSeconds(Math.max(1, availabilitySeconds)), 64);
    publicSlugs = new TtlCache<>(clock, ttl, 1);
  }

  /** Composant calculé ; {@code siteId} sert au filtrage par périmètre (jamais exposé). */
  public record ComponentSnapshot(
      UUID id,
      String name,
      String group,
      ComponentSourceType sourceType,
      UUID sourceId,
      UUID siteId,
      boolean sourceMissing,
      ComponentState state,
      Daily availability) {}

  public record IncidentSnapshot(
      String title,
      IncidentStatus status,
      IncidentImpact impact,
      String message,
      Set<UUID> componentIds,
      Instant createdAt,
      Instant resolvedAt,
      List<ViewIncidentUpdate> updates) {}

  public record Snapshot(
      UUID pageId,
      String slug,
      String title,
      String description,
      StatusVisibility visibility,
      boolean active,
      List<ComponentSnapshot> components,
      List<IncidentSnapshot> incidents,
      LocalDate availabilityFrom,
      Instant generatedAt) {}

  /** Instantané en cache (calculé au plus une fois par période). */
  public Snapshot get(UUID pageId) {
    return snapshots.get(pageId, () -> readOnly.execute(tx -> compute(pageId)));
  }

  /** Identifiant d'une page publique et active, sans requête tant que la liste est fraîche. */
  public Optional<UUID> publicPageId(String slug) {
    var map =
        publicSlugs.get(
            PUBLIC_SLUGS,
            () ->
                readOnly.execute(
                    tx -> {
                      var result = new HashMap<String, UUID>();
                      pages
                          .findByActiveTrueAndVisibility(StatusVisibility.PUBLIC)
                          .forEach(p -> result.put(p.getSlug(), p.getId()));
                      return Map.copyOf(result);
                    }));
    return Optional.ofNullable(map.get(slug));
  }

  /** Après une modification : la page suivante est recalculée. */
  public void invalidate(UUID pageId) {
    snapshots.invalidate(pageId);
    availability.invalidate(pageId);
    publicSlugs.clear();
  }

  /**
   * Rendu d'un instantané : seuls les composants dont le site passe {@code visibleSite} (périmètre
   * d'accès) sont gardés, l'état global est recalculé sur eux, et un incident lié uniquement à des
   * composants cachés est caché.
   */
  public static StatusPageView view(Snapshot snapshot, Predicate<UUID> visibleSite) {
    var visible = new LinkedHashMap<UUID, ComponentSnapshot>();
    for (ComponentSnapshot c : snapshot.components()) {
      if (visibleSite.test(c.siteId())) {
        visible.put(c.id(), c);
      }
    }
    var componentViews =
        visible.values().stream()
            .map(
                c ->
                    new ViewComponent(
                        c.name(),
                        c.group(),
                        c.state(),
                        c.availability() == null ? null : c.availability().overall(),
                        c.availability() == null ? List.of() : c.availability().days()))
            .toList();
    var incidentViews = new ArrayList<ViewIncident>();
    for (IncidentSnapshot i : snapshot.incidents()) {
      var names =
          i.componentIds().stream()
              .filter(visible::containsKey)
              .map(id -> visible.get(id).name())
              .toList();
      if (!i.componentIds().isEmpty() && names.isEmpty()) {
        continue;
      }
      incidentViews.add(
          new ViewIncident(
              i.title(),
              i.status(),
              i.impact(),
              i.message(),
              names,
              i.createdAt(),
              i.resolvedAt(),
              i.updates()));
    }
    return new StatusPageView(
        snapshot.title(),
        snapshot.description(),
        ComponentStateEvaluator.overall(visible.values().stream().map(c -> c.state()).toList()),
        snapshot.availabilityFrom(),
        AVAILABILITY_DAYS,
        componentViews,
        incidentViews,
        snapshot.generatedAt());
  }

  private Snapshot compute(UUID pageId) {
    StatusPage page = pages.findById(pageId).orElse(null);
    if (page == null) {
      return null;
    }
    Instant now = clock.instant();
    var list = components.findByPageIdOrderByPositionAscDisplayNameAsc(pageId);
    var current = currentStates(list, now);
    LocalDate first = firstDay(now);
    var daily = availability.get(pageId, () -> availabilityOf(list, now));
    var result = new ArrayList<ComponentSnapshot>();
    for (StatusComponent c : list) {
      var state = current.get(c.getId());
      result.add(
          new ComponentSnapshot(
              c.getId(),
              c.getDisplayName(),
              c.getGroupName(),
              c.getSourceType(),
              c.getSourceId(),
              state.siteId(),
              state.missing(),
              state.state(),
              daily.get(c.getId())));
    }
    var recent =
        incidents.findRecent(
            pageId,
            IncidentStatus.RESOLVED,
            now.minus(Duration.ofDays(INCIDENT_DAYS)),
            PageRequest.of(0, MAX_INCIDENTS));
    var incidentSnapshots = recent.stream().map(StatusSnapshotService::incident).toList();
    return new Snapshot(
        page.getId(),
        page.getSlug(),
        page.getTitle(),
        page.getDescription(),
        page.getVisibility(),
        page.isActive(),
        List.copyOf(result),
        incidentSnapshots,
        first,
        now);
  }

  private static IncidentSnapshot incident(StatusIncident i) {
    var updates = i.getUpdates();
    int from = Math.max(0, updates.size() - MAX_UPDATES);
    return new IncidentSnapshot(
        i.getTitle(),
        i.getStatus(),
        i.getImpact(),
        i.getMessage(),
        Set.copyOf(i.getComponentIds()),
        i.getCreatedAt(),
        i.getResolvedAt(),
        updates.subList(from, updates.size()).stream()
            .map(u -> new ViewIncidentUpdate(u.getStatus(), u.getMessage(), u.getCreatedAt()))
            .toList());
  }

  static LocalDate firstDay(Instant now) {
    return now.atOffset(ZoneOffset.UTC).toLocalDate().minusDays(AVAILABILITY_DAYS - 1L);
  }

  // --- État courant -----------------------------------------------------------------------------

  /** État d'un composant, site de rattachement et source disparue. */
  public record CurrentState(ComponentState state, UUID siteId, boolean missing) {}

  /** État courant de chaque composant (lectures groupées par type de source). */
  public Map<UUID, CurrentState> currentStates(List<StatusComponent> list, Instant now) {
    var probeIds = idsOf(list, ComponentSourceType.PROBE);
    var equipmentIds = idsOf(list, ComponentSourceType.EQUIPMENT);
    var siteIds = idsOf(list, ComponentSourceType.SITE);

    Map<UUID, ProbeRow> probes = sources.probes(probeIds);
    Map<UUID, EquipmentRow> equipments = sources.equipments(equipmentIds);
    Set<UUID> existingSites = new HashSet<>(sources.existingSites(siteIds));
    Map<UUID, EquipmentRow> siteEquipments = sources.equipmentsOfSites(existingSites);

    Set<UUID> allEquipments = new HashSet<>(equipments.keySet());
    probes.values().forEach(p -> allEquipments.add(p.equipmentId()));
    allEquipments.addAll(siteEquipments.keySet());
    Set<UUID> allSites = new HashSet<>(existingSites);
    probes.values().stream().map(ProbeRow::siteId).filter(s -> s != null).forEach(allSites::add);
    equipments.values().stream()
        .map(EquipmentRow::siteId)
        .filter(s -> s != null)
        .forEach(allSites::add);

    var alertsByEquipment = new HashMap<UUID, List<StatusSourceRepository.AlertRow>>();
    sources
        .activeAlerts(allEquipments)
        .forEach(
            a -> alertsByEquipment.computeIfAbsent(a.equipmentId(), k -> new ArrayList<>()).add(a));
    var maintenance = sources.maintenances(allEquipments, allSites, now, now.plusMillis(1), false);

    var result = new HashMap<UUID, CurrentState>();
    for (StatusComponent c : list) {
      UUID id = c.getSourceId();
      CurrentState state =
          switch (c.getSourceType()) {
            case PROBE -> {
              ProbeRow p = probes.get(id);
              if (p == null) {
                yield new CurrentState(ComponentState.UNKNOWN, null, true);
              }
              var alerts =
                  alertsByEquipment.getOrDefault(p.equipmentId(), List.of()).stream()
                      .filter(a -> a.serviceId() == null || a.serviceId().equals(id))
                      .map(a -> new AlertFact(a.type(), a.severity()))
                      .toList();
              var facts =
                  new ProbeFacts(
                      p.active(),
                      p.equipmentMonitored(),
                      p.lastUp(),
                      p.lastCheckedAt(),
                      p.consecutiveFailures(),
                      p.intervalSeconds());
              boolean inMaintenance = covered(maintenance, p.equipmentId(), p.siteId(), now);
              yield new CurrentState(
                  ComponentStateEvaluator.probe(facts, inMaintenance, alerts, now),
                  p.siteId(),
                  false);
            }
            case EQUIPMENT -> {
              EquipmentRow e = equipments.get(id);
              yield e == null
                  ? new CurrentState(ComponentState.UNKNOWN, null, true)
                  : new CurrentState(
                      equipmentState(e, alertsByEquipment, maintenance, now), e.siteId(), false);
            }
            case SITE -> {
              if (!existingSites.contains(id)) {
                yield new CurrentState(ComponentState.UNKNOWN, null, true);
              }
              var states =
                  siteEquipments.values().stream()
                      .filter(e -> id.equals(e.siteId()))
                      .map(e -> equipmentState(e, alertsByEquipment, maintenance, now))
                      .toList();
              boolean siteMaintenance = covered(maintenance, null, id, now);
              yield new CurrentState(
                  ComponentStateEvaluator.site(siteMaintenance, states), id, false);
            }
          };
      result.put(c.getId(), state);
    }
    return result;
  }

  private static ComponentState equipmentState(
      EquipmentRow e,
      Map<UUID, List<StatusSourceRepository.AlertRow>> alerts,
      List<MaintenanceRow> maintenance,
      Instant now) {
    var facts =
        alerts.getOrDefault(e.id(), List.of()).stream()
            .map(a -> new AlertFact(a.type(), a.severity()))
            .toList();
    return ComponentStateEvaluator.equipment(
        new EquipmentFacts(e.monitored(), e.status()),
        covered(maintenance, e.id(), e.siteId(), now),
        facts);
  }

  private static boolean covered(
      List<MaintenanceRow> rows, UUID equipmentId, UUID siteId, Instant now) {
    return ComponentStateEvaluator.covers(windowsFor(rows, equipmentId, siteId), now);
  }

  private static List<Window> windowsFor(List<MaintenanceRow> rows, UUID equipmentId, UUID siteId) {
    return rows.stream()
        .filter(
            m ->
                (equipmentId != null && equipmentId.equals(m.equipmentId()))
                    || (siteId != null && siteId.equals(m.siteId())))
        .map(m -> new Window(m.start(), m.end()))
        .toList();
  }

  private static Set<UUID> idsOf(List<StatusComponent> list, ComponentSourceType type) {
    var ids = new LinkedHashSet<UUID>();
    list.stream().filter(c -> c.getSourceType() == type).forEach(c -> ids.add(c.getSourceId()));
    return ids;
  }

  // --- Disponibilité ---------------------------------------------------------------------------

  private Map<UUID, Daily> availabilityOf(List<StatusComponent> list, Instant now) {
    LocalDate first = firstDay(now);
    Instant from = first.atStartOfDay(ZoneOffset.UTC).toInstant();
    var result = new HashMap<UUID, Daily>();
    for (StatusComponent c : list) {
      result.put(c.getId(), availabilityOf(c.getSourceType(), c.getSourceId(), from, now, first));
    }
    return result;
  }

  /**
   * Disponibilité d'une source : par équipement, mesures brutes de préférence puis agrégats
   * horaires, heures en maintenance de l'équipement ou de son site retirées, puis somme par jour.
   * Pour une sonde, les agrégats horaires (tous services confondus) ne servent que si l'équipement
   * n'a qu'une seule sonde ; sinon les jours sans mesure brute restent inconnus.
   */
  private Daily availabilityOf(
      ComponentSourceType type, UUID sourceId, Instant from, Instant now, LocalDate first) {
    Map<UUID, EquipmentRow> equipments;
    List<HourRow> raw;
    List<HourRow> hourly;
    switch (type) {
      case PROBE -> {
        ProbeRow probe = sources.probes(List.of(sourceId)).get(sourceId);
        if (probe == null) {
          return AvailabilityCalculator.summarize(List.of(), first, AVAILABILITY_DAYS);
        }
        equipments =
            Map.of(
                probe.equipmentId(),
                new EquipmentRow(probe.equipmentId(), probe.siteId(), true, null));
        raw = sources.rawProbeHours(probe.equipmentId(), sourceId, from);
        hourly =
            sources.serviceCount(probe.equipmentId()) == 1
                ? sources.hourlyEquipmentHours(List.of(probe.equipmentId()), from)
                : List.of();
      }
      case EQUIPMENT -> {
        equipments = sources.equipments(List.of(sourceId));
        raw = sources.rawEquipmentHours(equipments.keySet(), from);
        hourly = sources.hourlyEquipmentHours(equipments.keySet(), from);
      }
      case SITE -> {
        equipments = new HashMap<>(sources.equipmentsOfSites(List.of(sourceId)));
        equipments.values().removeIf(e -> !e.monitored());
        raw = sources.rawEquipmentHours(equipments.keySet(), from);
        hourly = sources.hourlyEquipmentHours(equipments.keySet(), from);
      }
      default -> throw new IllegalStateException();
    }
    if (equipments.isEmpty()) {
      return AvailabilityCalculator.summarize(List.of(), first, AVAILABILITY_DAYS);
    }
    var sites = new HashSet<UUID>();
    equipments.values().stream()
        .map(EquipmentRow::siteId)
        .filter(s -> s != null)
        .forEach(sites::add);
    var maintenance = sources.maintenances(equipments.keySet(), sites, from, now, true);
    var buckets = new ArrayList<HourBucket>();
    for (EquipmentRow e : equipments.values()) {
      buckets.addAll(
          AvailabilityCalculator.usable(
              bucketsOf(raw, e.id()),
              bucketsOf(hourly, e.id()),
              windowsFor(maintenance, e.id(), e.siteId())));
    }
    return AvailabilityCalculator.summarize(buckets, first, AVAILABILITY_DAYS);
  }

  private static List<HourBucket> bucketsOf(List<HourRow> rows, UUID equipmentId) {
    return rows.stream()
        .filter(r -> equipmentId.equals(r.equipmentId()))
        .map(r -> new HourBucket(r.hourStart(), r.samples(), r.sum()))
        .toList();
  }
}

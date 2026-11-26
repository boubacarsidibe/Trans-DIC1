package sn.ept.transdic1.inventory.service;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import sn.ept.transdic1.audit.service.AuditService;
import sn.ept.transdic1.common.scope.SiteScope;
import sn.ept.transdic1.inventory.domain.MonitoredService;
import sn.ept.transdic1.inventory.domain.ServiceDependency;
import sn.ept.transdic1.inventory.dto.ServiceDependencyDtos.DependencyRequest;
import sn.ept.transdic1.inventory.dto.ServiceDependencyDtos.DependencyResponse;
import sn.ept.transdic1.inventory.dto.ServiceDependencyDtos.ImportResult;
import sn.ept.transdic1.inventory.dto.ServiceDependencyDtos.ServiceNode;
import sn.ept.transdic1.inventory.repository.MonitoredServiceRepository;
import sn.ept.transdic1.inventory.repository.ServiceDependencyRepository;

/**
 * Dépendances entre services (ADR 0056) : saisie, suppression, import CSV. Une dépendance qui
 * fermerait un cycle est refusée : dans une boucle, aucune cause ne serait « la plus basse ».
 * Chaque service doit être dans le périmètre de l'utilisateur (ADR 0018).
 */
@Service
public class ServiceDependencyService {
  private static final String AUDIT_TARGET = "SERVICE_DEPENDENCY";
  private static final int MAX_IMPORT_LINES = 5000;

  private final ServiceDependencyRepository dependencies;
  private final MonitoredServiceRepository services;
  private final AuditService audit;
  private final SiteScope scope;
  private final Clock clock;

  public ServiceDependencyService(
      ServiceDependencyRepository dependencies,
      MonitoredServiceRepository services,
      AuditService audit,
      SiteScope scope,
      Clock clock) {
    this.dependencies = dependencies;
    this.services = services;
    this.audit = audit;
    this.scope = scope;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public List<DependencyResponse> list() {
    return dependencies.findAllWithServices().stream()
        .filter(d -> visible(d.getService()) && visible(d.getDependsOn()))
        .map(
            d ->
                new DependencyResponse(
                    d.getId(),
                    node(d.getService()),
                    node(d.getDependsOn()),
                    d.getCreatedBy(),
                    d.getCreatedAt()))
        .toList();
  }

  /** Services que l'on peut relier : sondes et services suivis, dans le périmètre. */
  @Transactional(readOnly = true)
  public List<ServiceNode> nodes() {
    return services.findAllWithEquipment().stream()
        .filter(this::visible)
        .map(ServiceDependencyService::node)
        .toList();
  }

  @Transactional
  public DependencyResponse create(DependencyRequest request) {
    var service = find(request.serviceId());
    var dependsOn = find(request.dependDeId());
    requireAssignable(service, dependsOn);
    if (service.getId().equals(dependsOn.getId())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Un service ne dépend pas de lui-même.");
    }
    if (dependencies.existsByServiceIdAndDependsOnId(service.getId(), dependsOn.getId())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Cette dépendance existe déjà.");
    }
    if (closesCycle(graph(), service.getId(), dependsOn.getId())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Dépendance refusée : "
              + dependsOn.getName()
              + " dépend déjà, directement ou non, de "
              + service.getName()
              + " (cycle).");
    }
    var saved =
        dependencies.save(
            new ServiceDependency(service, dependsOn, currentUsername(), clock.instant()));
    audit.record(
        "SERVICE_DEPENDENCY_CREATED", AUDIT_TARGET, saved.getId(), details(service, dependsOn));
    return new DependencyResponse(
        saved.getId(), node(service), node(dependsOn), saved.getCreatedBy(), saved.getCreatedAt());
  }

  @Transactional
  public void delete(UUID id) {
    var dependency =
        dependencies
            .findById(id)
            .filter(d -> visible(d.getService()) && visible(d.getDependsOn()))
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Dépendance introuvable"));
    dependencies.delete(dependency);
    audit.record(
        "SERVICE_DEPENDENCY_DELETED",
        AUDIT_TARGET,
        id,
        details(dependency.getService(), dependency.getDependsOn()));
  }

  /**
   * Import CSV : {@code code_equipement;service;code_equipement;service}, une dépendance par ligne.
   * Chaque ligne est jugée seule : une ligne fautive (service inconnu, hors périmètre, cycle) est
   * signalée, les autres sont créées.
   */
  @Transactional
  public ImportResult importCsv(String csv) {
    Map<String, MonitoredService> byKey = new HashMap<>();
    for (MonitoredService s : services.findAllWithEquipment()) {
      if (visible(s)) {
        byKey.put(key(s.getEquipment().getInventoryCode(), s.getName()), s);
      }
    }
    Map<UUID, Set<UUID>> graph = graph();
    int created = 0;
    int present = 0;
    List<String> errors = new ArrayList<>();
    String[] lines = csv.split("\\r?\\n");
    if (lines.length > MAX_IMPORT_LINES) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Au plus " + MAX_IMPORT_LINES + " lignes par import.");
    }
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i].strip();
      int number = i + 1;
      if (line.isEmpty()
          || line.startsWith("#")
          || (i == 0 && line.toLowerCase(Locale.ROOT).contains("service"))) {
        continue;
      }
      String[] cells = line.split("[;,]", -1);
      if (cells.length != 4) {
        errors.add("ligne " + number + " : 4 colonnes attendues (code;service;code;service)");
        continue;
      }
      var service = Optional.ofNullable(byKey.get(key(cells[0], cells[1])));
      var dependsOn = Optional.ofNullable(byKey.get(key(cells[2], cells[3])));
      if (service.isEmpty() || dependsOn.isEmpty()) {
        errors.add(
            "ligne "
                + number
                + " : service inconnu ou hors périmètre ("
                + (service.isEmpty()
                    ? cells[0].strip() + "/" + cells[1].strip()
                    : cells[2].strip() + "/" + cells[3].strip())
                + ")");
        continue;
      }
      UUID from = service.get().getId();
      UUID to = dependsOn.get().getId();
      if (from.equals(to)) {
        errors.add("ligne " + number + " : un service ne dépend pas de lui-même");
        continue;
      }
      if (graph.getOrDefault(from, Set.of()).contains(to)) {
        present++;
        continue;
      }
      if (closesCycle(graph, from, to)) {
        errors.add("ligne " + number + " : refusée, elle fermerait un cycle");
        continue;
      }
      var saved =
          dependencies.save(
              new ServiceDependency(
                  service.get(), dependsOn.get(), currentUsername(), clock.instant()));
      graph.computeIfAbsent(from, k -> new HashSet<>()).add(to);
      audit.record(
          "SERVICE_DEPENDENCY_CREATED",
          AUDIT_TARGET,
          saved.getId(),
          details(service.get(), dependsOn.get()) + " import=csv");
      created++;
    }
    return new ImportResult(created, present, errors);
  }

  /** Vrai si « from dépend de to » fermerait un cycle : to mène déjà à from. */
  static boolean closesCycle(Map<UUID, Set<UUID>> graph, UUID from, UUID to) {
    var pending = new ArrayDeque<UUID>();
    var seen = new HashSet<UUID>();
    pending.push(to);
    while (!pending.isEmpty()) {
      UUID current = pending.pop();
      if (current.equals(from)) {
        return true;
      }
      if (seen.add(current)) {
        graph.getOrDefault(current, Set.of()).forEach(pending::push);
      }
    }
    return false;
  }

  private Map<UUID, Set<UUID>> graph() {
    Map<UUID, Set<UUID>> graph = new HashMap<>();
    for (Object[] edge : dependencies.edges()) {
      graph.computeIfAbsent((UUID) edge[0], k -> new HashSet<>()).add((UUID) edge[1]);
    }
    return graph;
  }

  private MonitoredService find(UUID id) {
    return services
        .findById(id)
        .filter(this::visible)
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Service introuvable"));
  }

  private void requireAssignable(MonitoredService... list) {
    for (MonitoredService s : list) {
      scope.requireAssignable(s.getEquipment().getSite().getId());
    }
  }

  private boolean visible(MonitoredService service) {
    var site = service.getEquipment().getSite();
    return scope.allows(site == null ? null : site.getId());
  }

  private static String key(String code, String name) {
    return code.strip().toLowerCase(Locale.ROOT) + "|" + name.strip().toLowerCase(Locale.ROOT);
  }

  private static ServiceNode node(MonitoredService s) {
    var equipment = s.getEquipment();
    return new ServiceNode(
        s.getId(),
        s.getName(),
        s.getType(),
        equipment.getId(),
        equipment.getName(),
        equipment.getInventoryCode(),
        equipment.getSite() == null ? null : equipment.getSite().getName());
  }

  private static String details(MonitoredService service, MonitoredService dependsOn) {
    return "service="
        + service.getName()
        + "@"
        + service.getEquipment().getInventoryCode()
        + " dependDe="
        + dependsOn.getName()
        + "@"
        + dependsOn.getEquipment().getInventoryCode();
  }

  private static String currentUsername() {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || auth.getName() == null) {
      return null;
    }
    String name = auth.getName();
    return name.length() > 64 ? name.substring(0, 64) : name;
  }
}

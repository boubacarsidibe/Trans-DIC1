package sn.ept.transdic1.collection.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import sn.ept.transdic1.audit.service.AuditService;
import sn.ept.transdic1.collection.dto.AgentSnapshotDto;
import sn.ept.transdic1.collection.dto.AgentSnapshotDto.ServiceState;
import sn.ept.transdic1.collection.dto.ServiceDiscoveryDtos.DiscoveredService;
import sn.ept.transdic1.collection.dto.ServiceDiscoveryDtos.DiscoveredServices;
import sn.ept.transdic1.collection.dto.ServiceDiscoveryDtos.FollowedNames;
import sn.ept.transdic1.collection.dto.ServiceDiscoveryDtos.ListeningPort;
import sn.ept.transdic1.common.scope.SiteScope;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.MonitoredService;
import sn.ept.transdic1.inventory.domain.ServiceType;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.MonitoredServiceRepository;

/**
 * Services découverts par l'agent et services suivis (ADR 0056, ADR 0060).
 *
 * <p>Suivre un service crée un service supervisé de type {@code AGENT_SERVICE} : l'agent lit la
 * liste des services suivis de son équipement et remonte leur état dans {@code service.up[nom]},
 * sans que sa configuration soit modifiée sur la machine. La plateforme ne transmet que des noms,
 * validés par le même motif que l'agent : l'agent ne fait qu'interroger l'état d'un service, il
 * n'exécute rien de ce qu'il reçoit.
 *
 * <p>Depuis l'agent 2.3, l'instantané porte tous les services avec leur mode de démarrage. Un
 * service à démarrage automatique, vu démarré, est suivi d'office, sauf s'il est exclu ou si un
 * administrateur l'a ignoré. Un service vu arrêté n'est pas suivi d'office : la première découverte
 * ne déclenche aucune alerte ; c'est un arrêt constaté ensuite, après le délai de confirmation de
 * la règle, qui alerte.
 */
@Service
public class ServiceDiscoveryService {
  private static final String AUDIT_TARGET = "MONITORED_SERVICE";
  private static final String NOT_FOUND = "Équipement introuvable";
  private static final Pattern SERVICE_NAME = Pattern.compile("[A-Za-z0-9_.@-]{1,64}");
  private static final Set<String> AUTOMATIC = Set.of("AUTO", "AUTO_DELAYED");

  private static boolean automatic(String startMode) {
    return startMode != null && AUTOMATIC.contains(startMode);
  }

  /**
   * Garde-fou : un agent ne suit pas des centaines de services à la fois. 200 couvre les services à
   * démarrage automatique d'un Windows ordinaire (une centaine) ; l'agent 2.3 accepte autant.
   */
  static final int MAX_FOLLOWED = 200;

  private final EquipmentRepository equipments;
  private final MonitoredServiceRepository services;
  private final AgentSnapshotService snapshots;
  private final AuditService audit;
  private final SiteScope scope;
  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final List<Pattern> exclusions;
  private final boolean autoFollow;

  public ServiceDiscoveryService(
      EquipmentRepository equipments,
      MonitoredServiceRepository services,
      AgentSnapshotService snapshots,
      AuditService audit,
      SiteScope scope,
      JdbcTemplate jdbc,
      Clock clock,
      @Value(
              "${collection.services.exclude:systemd-*,getty@*,serial-getty@*,user@*,user-runtime-dir@*,dbus*,polkit*,"
                  + "accounts-daemon,rtkit-daemon,upower,udisks2,ModemManager,wpa_supplicant}")
          String exclusions,
      @Value("${collection.services.auto-follow:true}") boolean autoFollow) {
    this.equipments = equipments;
    this.services = services;
    this.snapshots = snapshots;
    this.audit = audit;
    this.scope = scope;
    this.jdbc = jdbc;
    this.clock = clock;
    this.autoFollow = autoFollow;
    this.exclusions =
        Arrays.stream(exclusions.split(","))
            .map(String::strip)
            .filter(p -> !p.isEmpty())
            .map(ServiceDiscoveryService::glob)
            .toList();
  }

  /**
   * Services du dernier instantané, moins la liste d'exclusion, avec leur état, leur mode de
   * démarrage et leur suivi ; plus les services suivis que l'agent ne voit plus.
   */
  @Transactional(readOnly = true)
  public DiscoveredServices discovered(UUID equipmentId) {
    var snapshot = snapshots.read(equipmentId);
    AgentSnapshotDto dto = snapshot.snapshot();
    Instant at = snapshot.receivedAt();
    Map<String, MonitoredService> followed = followedByName(equipmentId);
    Set<String> ignored = ignoredNames(equipmentId);
    Map<String, DiscoveredService> result = new LinkedHashMap<>();
    int excluded = 0;
    for (ServiceState state : states(dto)) {
      if (excluded(state.name())) {
        excluded++;
        continue;
      }
      result.put(state.name(), line(state.name(), state, followed.get(state.name()), ignored, at));
    }
    // Un service suivi que l'agent ne voit plus est arrêté : il reste affiché.
    followed.forEach(
        (name, service) ->
            result.putIfAbsent(
                name,
                line(
                    name,
                    new ServiceState(name, false, service.getStartMode()),
                    service,
                    ignored,
                    at)));
    List<ListeningPort> ports = new ArrayList<>();
    for (var port :
        dto.listeningPorts() == null
            ? List.<AgentSnapshotDto.ListeningPort>of()
            : dto.listeningPorts()) {
      ports.add(new ListeningPort(port.protocol(), port.address(), port.port()));
    }
    ports.sort(
        (a, b) ->
            a.port() != b.port()
                ? Integer.compare(a.port(), b.port())
                : a.protocole().compareTo(b.protocole()));
    return new DiscoveredServices(at, new ArrayList<>(result.values()), ports, excluded);
  }

  /**
   * Instantané reçu : les services à démarrage automatique vus démarrés sont suivis d'office, sauf
   * exclus ou ignorés ; le mode de démarrage des services déjà suivis est tenu à jour. Sans effet
   * pour un agent qui n'envoie pas les états (avant 2.3) ou si l'option est coupée.
   */
  @Transactional
  public List<String> followAutomatic(UUID equipmentId, AgentSnapshotDto snapshot) {
    if (snapshot.serviceStates() == null) {
      return List.of();
    }
    Map<String, MonitoredService> existing = new LinkedHashMap<>();
    for (MonitoredService s : services.findByEquipmentIdOrderByName(equipmentId)) {
      existing.put(s.getName(), s);
    }
    Map<String, ServiceState> byName = new LinkedHashMap<>();
    for (ServiceState state : snapshot.serviceStates()) {
      byName.putIfAbsent(state.name(), state);
    }
    // Le mode de démarrage des services suivis sert au message de l'alerte.
    byName.forEach(
        (name, state) -> {
          var service = existing.get(name);
          if (service != null
              && service.getType() == ServiceType.AGENT_SERVICE
              && state.startMode() != null
              && !state.startMode().equals(service.getStartMode())) {
            service.setStartMode(state.startMode());
          }
        });
    if (!autoFollow) {
      return List.of();
    }
    var equipment = equipments.findById(equipmentId).orElse(null);
    if (equipment == null) {
      return List.of();
    }
    Set<String> ignored = ignoredNames(equipmentId);
    long count =
        existing.values().stream()
            .filter(s -> s.getType() == ServiceType.AGENT_SERVICE && s.isActive())
            .count();
    List<String> added = new ArrayList<>();
    for (ServiceState state : byName.values()) {
      String name = state.name();
      if (count >= MAX_FOLLOWED) {
        break;
      }
      if (!state.active()
          || !automatic(state.startMode())
          || !SERVICE_NAME.matcher(name).matches()
          || excluded(name)
          || ignored.contains(name)
          || existing.containsKey(name)) {
        continue;
      }
      var service = new MonitoredService(equipment, name, ServiceType.AGENT_SERVICE);
      service.setProtocol("agent");
      service.setActive(true);
      service.setStartMode(state.startMode());
      service.setAutoFollowed(true);
      services.save(service);
      added.add(name);
      count++;
    }
    if (!added.isEmpty()) {
      services.flush();
      String names = String.join(",", added);
      // Action de la plateforme, à la réception de l'instantané de l'agent : tracée au nom
      // « agent », le principal de l'agent n'étant pas un compte.
      jdbc.update(
          "INSERT INTO audit_entry (id, username, action, target_type, target_id, details_sanitized,"
              + " performed_at) VALUES (?, 'agent', 'AGENT_SERVICES_AUTO_FOLLOWED', 'EQUIPMENT', ?,"
              + " ?, ?)",
          UUID.randomUUID(),
          equipmentId,
          "equipement="
              + equipment.getInventoryCode()
              + " nombre="
              + added.size()
              + " services="
              + (names.length() > 400 ? names.substring(0, 400) + "…" : names),
          Timestamp.from(clock.instant()));
    }
    return added;
  }

  /**
   * Suit un service système : l'agent remontera son état au prochain passage. Un service ignoré
   * redevient suivi (c'est ainsi qu'un administrateur le réactive).
   */
  @Transactional
  public DiscoveredService follow(UUID equipmentId, String name) {
    Equipment equipment = assignable(equipmentId);
    if (!SERVICE_NAME.matcher(name).matches()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nom de service invalide");
    }
    boolean wasIgnored =
        jdbc.update(
                "DELETE FROM ignored_agent_service WHERE equipment_id = ? AND name = ?",
                equipmentId,
                name)
            > 0;
    var existing =
        services.findByEquipmentIdOrderByName(equipmentId).stream()
            .filter(s -> s.getName().equals(name))
            .findFirst();
    if (existing.isPresent()) {
      if (existing.get().getType() == ServiceType.AGENT_SERVICE) {
        return followedLine(existing.get());
      }
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Une sonde de cet équipement porte déjà le nom « " + name + " ».");
    }
    if (followedByName(equipmentId).size() >= MAX_FOLLOWED) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Au plus " + MAX_FOLLOWED + " services suivis par équipement.");
    }
    var service = new MonitoredService(equipment, name, ServiceType.AGENT_SERVICE);
    service.setProtocol("agent");
    service.setActive(true);
    service.setStartMode(lastStartMode(equipmentId, name));
    services.saveAndFlush(service);
    audit.record(
        wasIgnored ? "AGENT_SERVICE_REACTIVATED" : "AGENT_SERVICE_FOLLOWED",
        AUDIT_TARGET,
        service.getId(),
        "equipement=" + equipment.getInventoryCode() + " service=" + name);
    return followedLine(service);
  }

  /**
   * Cesse de suivre un service : il devient « ignoré » et ne sera plus suivi d'office. Ses
   * dépendances disparaissent avec lui, l'historique reste.
   */
  @Transactional
  public void unfollow(UUID equipmentId, UUID serviceId) {
    Equipment equipment = assignable(equipmentId);
    var service =
        services
            .findById(serviceId)
            .filter(s -> s.getEquipment().getId().equals(equipmentId))
            .filter(s -> s.getType() == ServiceType.AGENT_SERVICE)
            .orElseThrow(
                () ->
                    new ResponseStatusException(HttpStatus.NOT_FOUND, "Service suivi introuvable"));
    services.delete(service);
    jdbc.update(
        "INSERT INTO ignored_agent_service (id, equipment_id, name, ignored_by, ignored_at)"
            + " VALUES (?, ?, ?, ?, ?) ON CONFLICT (equipment_id, name) DO NOTHING",
        UUID.randomUUID(),
        equipmentId,
        service.getName(),
        currentUsername(),
        Timestamp.from(clock.instant()));
    audit.record(
        "AGENT_SERVICE_UNFOLLOWED",
        AUDIT_TARGET,
        serviceId,
        "equipement=" + equipment.getInventoryCode() + " service=" + service.getName() + " ignore");
  }

  /** Noms à remonter par l'agent de cet équipement (authentifié par son jeton). */
  @Transactional(readOnly = true)
  public FollowedNames followedNames(UUID equipmentId) {
    return new FollowedNames(
        followedByName(equipmentId).keySet().stream()
            .filter(name -> SERVICE_NAME.matcher(name).matches())
            .limit(MAX_FOLLOWED)
            .toList());
  }

  boolean excluded(String name) {
    return exclusions.stream().anyMatch(p -> p.matcher(name).matches());
  }

  /** États du dernier instantané ; un agent 2.2 n'envoie que les noms des services démarrés. */
  private static List<ServiceState> states(AgentSnapshotDto dto) {
    if (dto.serviceStates() != null) {
      return dto.serviceStates();
    }
    List<ServiceState> states = new ArrayList<>();
    for (String name : dto.services() == null ? List.<String>of() : dto.services()) {
      states.add(new ServiceState(name, true, null));
    }
    return states;
  }

  private static DiscoveredService line(
      String name, ServiceState state, MonitoredService followed, Set<String> ignored, Instant at) {
    String mode = state.startMode();
    String status = followed != null ? "SUIVI" : ignored.contains(name) ? "IGNORE" : "NON_SUIVI";
    return new DiscoveredService(
        name,
        followed != null,
        followed == null ? null : followed.getId(),
        state.active() ? "ACTIF" : "ARRETE",
        mode,
        status,
        automatic(mode) && !state.active(),
        followed != null && followed.isAutoFollowed(),
        at);
  }

  private static DiscoveredService followedLine(MonitoredService service) {
    return new DiscoveredService(
        service.getName(),
        true,
        service.getId(),
        null,
        service.getStartMode(),
        "SUIVI",
        false,
        service.isAutoFollowed(),
        null);
  }

  private String lastStartMode(UUID equipmentId, String name) {
    try {
      var dto = snapshots.read(equipmentId).snapshot();
      if (dto.serviceStates() == null) {
        return null;
      }
      return dto.serviceStates().stream()
          .filter(s -> s.name().equals(name))
          .map(ServiceState::startMode)
          .findFirst()
          .orElse(null);
    } catch (ResponseStatusException noSnapshot) {
      return null;
    }
  }

  private Set<String> ignoredNames(UUID equipmentId) {
    return new HashSet<>(
        jdbc.queryForList(
            "SELECT name FROM ignored_agent_service WHERE equipment_id = ?",
            String.class,
            equipmentId));
  }

  private Map<String, MonitoredService> followedByName(UUID equipmentId) {
    Map<String, MonitoredService> map = new LinkedHashMap<>();
    for (MonitoredService s : services.findByEquipmentIdOrderByName(equipmentId)) {
      if (s.getType() == ServiceType.AGENT_SERVICE && s.isActive()) {
        map.put(s.getName(), s);
      }
    }
    return map;
  }

  private Equipment assignable(UUID equipmentId) {
    var equipment =
        equipments
            .findById(equipmentId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, NOT_FOUND));
    scope.requireVisible(equipment.getSite().getId(), NOT_FOUND);
    return equipment;
  }

  private static String currentUsername() {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    return authentication == null ? null : authentication.getName();
  }

  /** Motif de la liste d'exclusion : « * » pour n'importe quelle suite de caractères. */
  private static Pattern glob(String pattern) {
    StringBuilder regex = new StringBuilder();
    for (char c : pattern.toCharArray()) {
      regex.append(c == '*' ? ".*" : Pattern.quote(String.valueOf(c)));
    }
    return Pattern.compile(regex.toString());
  }
}

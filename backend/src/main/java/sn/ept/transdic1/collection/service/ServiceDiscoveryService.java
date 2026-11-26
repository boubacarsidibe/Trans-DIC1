package sn.ept.transdic1.collection.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import sn.ept.transdic1.audit.service.AuditService;
import sn.ept.transdic1.collection.dto.AgentSnapshotDto;
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
 * Services découverts par l'agent et services suivis à la demande (ADR 0056).
 *
 * <p>Suivre un service crée un service supervisé de type {@code AGENT_SERVICE} : l'agent lit la
 * liste des services suivis de son équipement et remonte leur état dans {@code service.up[nom]},
 * sans que sa configuration soit modifiée sur la machine. La plateforme ne transmet que des noms,
 * validés par le même motif que l'agent : l'agent ne fait qu'interroger l'état d'un service, il
 * n'exécute rien de ce qu'il reçoit.
 */
@Service
public class ServiceDiscoveryService {
  private static final String AUDIT_TARGET = "MONITORED_SERVICE";
  private static final String NOT_FOUND = "Équipement introuvable";
  private static final Pattern SERVICE_NAME = Pattern.compile("[A-Za-z0-9_.@-]{1,64}");

  /** Garde-fou : un agent ne suit pas des centaines de services à la fois. */
  static final int MAX_FOLLOWED = 50;

  private final EquipmentRepository equipments;
  private final MonitoredServiceRepository services;
  private final AgentSnapshotService snapshots;
  private final AuditService audit;
  private final SiteScope scope;
  private final List<Pattern> exclusions;

  public ServiceDiscoveryService(
      EquipmentRepository equipments,
      MonitoredServiceRepository services,
      AgentSnapshotService snapshots,
      AuditService audit,
      SiteScope scope,
      @Value(
              "${collection.services.exclude:systemd-*,getty@*,serial-getty@*,user@*,user-runtime-dir@*,dbus*,polkit*,"
                  + "accounts-daemon,rtkit-daemon,upower,udisks2,ModemManager,wpa_supplicant}")
          String exclusions) {
    this.equipments = equipments;
    this.services = services;
    this.snapshots = snapshots;
    this.audit = audit;
    this.scope = scope;
    this.exclusions =
        Arrays.stream(exclusions.split(","))
            .map(String::strip)
            .filter(p -> !p.isEmpty())
            .map(ServiceDiscoveryService::glob)
            .toList();
  }

  /** Services et ports du dernier instantané, moins la liste d'exclusion, avec leur suivi. */
  @Transactional(readOnly = true)
  public DiscoveredServices discovered(UUID equipmentId) {
    var snapshot = snapshots.read(equipmentId);
    AgentSnapshotDto dto = snapshot.snapshot();
    Map<String, MonitoredService> followed = followedByName(equipmentId);
    Map<String, DiscoveredService> result = new LinkedHashMap<>();
    int excluded = 0;
    for (String name : dto.services() == null ? List.<String>of() : dto.services()) {
      if (excluded(name)) {
        excluded++;
        continue;
      }
      var service = followed.get(name);
      result.put(
          name,
          new DiscoveredService(name, service != null, service == null ? null : service.getId()));
    }
    // Un service suivi que l'agent ne voit plus actif est arrêté : il reste affiché.
    followed.forEach(
        (name, service) ->
            result.putIfAbsent(name, new DiscoveredService(name, true, service.getId())));
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
    return new DiscoveredServices(
        snapshot.receivedAt(), new ArrayList<>(result.values()), ports, excluded);
  }

  /** Suit un service système : l'agent remontera son état au prochain instantané. */
  @Transactional
  public DiscoveredService follow(UUID equipmentId, String name) {
    Equipment equipment = assignable(equipmentId);
    if (!SERVICE_NAME.matcher(name).matches()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nom de service invalide");
    }
    var existing =
        services.findByEquipmentIdOrderByName(equipmentId).stream()
            .filter(s -> s.getName().equals(name))
            .findFirst();
    if (existing.isPresent()) {
      if (existing.get().getType() == ServiceType.AGENT_SERVICE) {
        return new DiscoveredService(name, true, existing.get().getId());
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
    services.saveAndFlush(service);
    audit.record(
        "AGENT_SERVICE_FOLLOWED",
        AUDIT_TARGET,
        service.getId(),
        "equipement=" + equipment.getInventoryCode() + " service=" + name);
    return new DiscoveredService(name, true, service.getId());
  }

  /** Cesse de suivre un service : ses dépendances disparaissent avec lui, l'historique reste. */
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
    audit.record(
        "AGENT_SERVICE_UNFOLLOWED",
        AUDIT_TARGET,
        serviceId,
        "equipement=" + equipment.getInventoryCode() + " service=" + service.getName());
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

  /** Motif de la liste d'exclusion : « * » pour n'importe quelle suite de caractères. */
  private static Pattern glob(String pattern) {
    StringBuilder regex = new StringBuilder();
    for (char c : pattern.toCharArray()) {
      regex.append(c == '*' ? ".*" : Pattern.quote(String.valueOf(c)));
    }
    return Pattern.compile(regex.toString());
  }
}

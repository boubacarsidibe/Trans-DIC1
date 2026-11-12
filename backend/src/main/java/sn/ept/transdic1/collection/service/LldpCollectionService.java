package sn.ept.transdic1.collection.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import sn.ept.transdic1.collection.service.SnmpClient.SnmpFailure;
import sn.ept.transdic1.collection.service.SnmpClient.Target;
import sn.ept.transdic1.common.scheduling.JobGuard;
import sn.ept.transdic1.inventory.domain.CollectionConfiguration;
import sn.ept.transdic1.inventory.domain.CollectionMode;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.LldpNeighbour;
import sn.ept.transdic1.inventory.repository.CollectionConfigurationRepository;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.LldpNeighbourRepository;

/**
 * Lecture des voisins LLDP par SNMP (ADR 0034) : ce que chaque équipement dit voir au bout de ses
 * ports.
 *
 * <p>La topologie de la plateforme était entièrement saisie à la main. Les commutateurs, eux,
 * savent déjà qui est branché où : LLDP est fait pour cela, et tous les outils comparables s'en
 * servent.
 *
 * <p>Une fois par heure, pas plus : un voisinage ne change pas toutes les minutes, et cinq parcours
 * de table par équipement coûtent cher. Un équipement qui ne parle pas LLDP rend une table vide, ce
 * qui ne coûte presque rien.
 *
 * <p><b>Rien n'est modifié dans la topologie.</b> Les voisins sont enregistrés et rapprochés de
 * l'inventaire quand c'est possible ; c'est un administrateur qui accepte ou écarte chaque lien
 * proposé. Un équipement s'annonce lui-même en LLDP : rien ne garantit qu'il dise vrai, et réécrire
 * une topologie faite à la main sur cette seule foi serait téméraire.
 */
@Service
public class LldpCollectionService {
  private static final Logger LOG = LoggerFactory.getLogger(LldpCollectionService.class);

  /** Lignes lues au plus par table : un équipement bavard ne doit pas bloquer la collecte. */
  static final int MAX_ROWS = 500;

  /** Rapprochement par l'adresse de gestion que le voisin annonce. */
  static final String MATCH_ADDRESS = "ADRESSE";

  /** Rapprochement par le nom que le voisin annonce. */
  static final String MATCH_NAME = "NOM";

  private final SnmpClient client;
  private final SnmpTargets targets;
  private final CollectionConfigurationRepository configurations;
  private final EquipmentRepository equipments;
  private final LldpNeighbourRepository neighbours;
  private final JobGuard guard;
  private final TransactionTemplate transaction;
  private final Clock clock;
  private final boolean enabled;
  private final Duration interval;

  public LldpCollectionService(
      SnmpClient client,
      SnmpTargets targets,
      CollectionConfigurationRepository configurations,
      EquipmentRepository equipments,
      LldpNeighbourRepository neighbours,
      JobGuard guard,
      org.springframework.transaction.PlatformTransactionManager transactions,
      Clock clock,
      @Value("${collection.snmp.lldp.enabled:true}") boolean enabled,
      @Value("${collection.snmp.lldp.interval-minutes:60}") long intervalMinutes) {
    this.client = client;
    this.targets = targets;
    this.configurations = configurations;
    this.equipments = equipments;
    this.neighbours = neighbours;
    this.guard = guard;
    this.transaction = new TransactionTemplate(transactions);
    this.clock = clock;
    this.enabled = enabled;
    this.interval = Duration.ofMinutes(Math.max(5, intervalMinutes));
  }

  @Scheduled(
      fixedDelayString = "${collection.snmp.lldp.scan-interval-ms:300000}",
      initialDelayString = "${collection.snmp.lldp.scan-interval-ms:300000}")
  public void scheduledScan() {
    if (enabled) {
      guard.run("collection.snmp-lldp", Duration.ofMinutes(15), this::collectAll);
    }
  }

  /** Un passage sur tous les équipements supervisés en SNMP ; retourne le nombre lu. */
  public int collectAll() {
    List<UUID> due =
        transaction.execute(
            status ->
                configurations.findAll().stream()
                    .filter(CollectionConfiguration::isActive)
                    .filter(configuration -> configuration.getMode() == CollectionMode.SNMP)
                    .filter(configuration -> configuration.getEquipment().isMonitoringEnabled())
                    .map(CollectionConfiguration::getId)
                    .toList());
    if (due == null || due.isEmpty()) {
      return 0;
    }
    int done = 0;
    for (UUID id : due) {
      if (collectSafely(id)) {
        done++;
      }
    }
    return done;
  }

  private boolean collectSafely(UUID configurationId) {
    try {
      return Boolean.TRUE.equals(transaction.execute(status -> collectOne(configurationId)));
    } catch (RuntimeException e) {
      // Un équipement défaillant ne doit jamais arrêter les autres.
      LOG.error("Voisins LLDP en erreur pour la configuration {} : {}", configurationId, e);
      return false;
    }
  }

  private boolean collectOne(UUID configurationId) {
    CollectionConfiguration configuration = configurations.findById(configurationId).orElse(null);
    if (configuration == null) {
      return false;
    }
    Equipment equipment = configuration.getEquipment();
    List<LldpNeighbour> known = neighbours.findByEquipmentId(equipment.getId());
    Instant now = clock.instant();
    if (!known.isEmpty() && !isDue(known, now)) {
      return false;
    }
    Target target = targets.of(configuration, equipment.getManagementAddress());
    try {
      update(equipment, read(target), known, now);
      return true;
    } catch (SnmpFailure e) {
      LOG.debug(
          "Voisins LLDP de {} illisibles : {}", equipment.getManagementAddress(), e.getMessage());
      return false;
    }
  }

  /** La lecture est due quand le plus ancien voisin connu date de plus d'un intervalle. */
  private boolean isDue(List<LldpNeighbour> known, Instant now) {
    return known.stream()
        .map(LldpNeighbour::getLastSeenAt)
        .max(Instant::compareTo)
        .map(latest -> latest.isBefore(now.minus(interval)))
        .orElse(true);
  }

  /** Parcourt les cinq colonnes utiles et rend les voisins annoncés. */
  List<LldpTable.Neighbour> read(Target target) throws SnmpFailure {
    var chassis = client.walk(target, LldpTable.REM_CHASSIS_ID, MAX_ROWS);
    if (chassis.isEmpty()) {
      // L'équipement ne parle pas LLDP, ou n'a aucun voisin : un seul parcours a suffi.
      return List.of();
    }
    Map<String, String> addresses =
        LldpTable.managementAddresses(client.walk(target, LldpTable.REM_MAN_ADDR_TABLE, MAX_ROWS));
    Map<Integer, String> ports =
        LldpTable.localPorts(client.walk(target, LldpTable.LOC_PORT_ID, MAX_ROWS));
    return LldpTable.parse(
        chassis,
        client.walk(target, LldpTable.REM_PORT_ID, MAX_ROWS),
        client.walk(target, LldpTable.REM_PORT_DESC, MAX_ROWS),
        client.walk(target, LldpTable.REM_SYS_NAME, MAX_ROWS),
        client.walk(target, LldpTable.REM_SYS_DESC, MAX_ROWS),
        ports,
        addresses);
  }

  /** Enregistre ce qui a été vu : création, mise à jour, et oubli de ce qui a disparu. */
  public void update(
      Equipment equipment, List<LldpTable.Neighbour> seen, List<LldpNeighbour> known, Instant now) {
    var byKey = new HashMap<String, LldpNeighbour>();
    known.forEach(neighbour -> byKey.put(key(neighbour), neighbour));
    var directory = directory();
    for (LldpTable.Neighbour found : seen) {
      String key = found.localPortIndex() + "|" + found.chassisId() + "|" + found.portId();
      LldpNeighbour neighbour = byKey.remove(key);
      if (neighbour == null) {
        neighbour =
            new LldpNeighbour(
                equipment,
                found.localPortIndex(),
                found.chassisId(),
                found.portId() == null ? "" : found.portId(),
                now);
      }
      neighbour.setLocalPort(found.localPort());
      neighbour.setRemotePortDescription(found.portDescription());
      neighbour.setRemoteSystemName(found.systemName());
      neighbour.setRemoteSystemDescription(found.systemDescription());
      neighbour.setRemoteManagementAddress(found.managementAddress());
      match(neighbour, equipment, directory);
      neighbour.setLastSeenAt(now);
      neighbours.save(neighbour);
    }
    // Ce qui n'a pas été revu a disparu du voisinage : un câble débranché ne doit pas rester
    // proposé indéfiniment.
    byKey.values().forEach(neighbours::delete);
  }

  private String key(LldpNeighbour neighbour) {
    return neighbour.getLocalPortIndex()
        + "|"
        + neighbour.getRemoteChassisId()
        + "|"
        + neighbour.getRemotePortId();
  }

  /** Équipements supervisés par adresse de gestion et par nom court, lus une fois par passage. */
  private record Directory(Map<String, UUID> byAddress, Map<String, UUID> byName) {}

  private Directory directory() {
    var byAddress = new HashMap<String, UUID>();
    var byName = new HashMap<String, UUID>();
    for (Equipment equipment : equipments.findAll()) {
      if (equipment.getManagementAddress() != null) {
        byAddress.putIfAbsent(equipment.getManagementAddress().trim(), equipment.getId());
      }
      String name = LldpTable.shortName(equipment.getName());
      if (name != null) {
        byName.putIfAbsent(name, equipment.getId());
      }
    }
    return new Directory(byAddress, byName);
  }

  /**
   * Reconnaît l'équipement au bout du câble : d'abord par l'adresse de gestion qu'il annonce, qui
   * ne trompe pas ; à défaut par son nom, qui peut se répéter dans un parc mal tenu. Un voisin
   * qu'on ne reconnaît pas reste affiché : c'est souvent un équipement à inventorier.
   */
  private void match(LldpNeighbour neighbour, Equipment local, Directory directory) {
    UUID matched = null;
    String reason = null;
    if (neighbour.getRemoteManagementAddress() != null) {
      matched = directory.byAddress().get(neighbour.getRemoteManagementAddress());
      reason = matched == null ? null : MATCH_ADDRESS;
    }
    if (matched == null && neighbour.getRemoteSystemName() != null) {
      matched = directory.byName().get(LldpTable.shortName(neighbour.getRemoteSystemName()));
      reason = matched == null ? null : MATCH_NAME;
    }
    if (matched == null || matched.equals(local.getId())) {
      // Un équipement qui se verrait lui-même (boucle de câblage) n'est pas un voisin utile.
      neighbour.setMatchedEquipment(null);
      neighbour.setMatchReason(null);
      return;
    }
    neighbour.setMatchedEquipment(equipments.getReferenceById(matched));
    neighbour.setMatchReason(reason.toUpperCase(Locale.ROOT));
  }
}

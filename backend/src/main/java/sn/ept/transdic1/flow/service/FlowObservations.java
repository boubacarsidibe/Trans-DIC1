package sn.ept.transdic1.flow.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import sn.ept.transdic1.flow.receiver.FlowRecord;

/**
 * Ce qu'il faut retenir d'une minute de flux pour reconnaître un balayage ou une inondation (S6,
 * ADR 0033) : qui parle à combien de machines, sur combien de ports, et qui reçoit de combien de
 * sources.
 *
 * <p><b>Pourquoi ne pas lire la base ?</b> Parce que les tables n'en gardent que les plus grosses
 * conversations de la minute (ADR 0032), et qu'un balayage est fait de milliers d'échanges
 * minuscules — exactement ceux que le plafonnement écarte. Un scan y serait invisible. Le comptage
 * se fait donc ici, à la réception, sur tous les flux.
 *
 * <p>Tout est borné, parce que c'est le réseau qui remplit ces structures : un nombre maximal
 * d'adresses suivies, et un nombre maximal d'interlocuteurs retenus pour chacune. Quand un plafond
 * est atteint, le comptage s'arrête mais le fait est noté : l'alerte dira « plus de {@code N} »,
 * jamais un chiffre faux.
 */
public class FlowObservations {
  /** Adresses suivies en même temps, par équipement et par minute. */
  private final int maxTracked;

  /** Interlocuteurs ou ports retenus pour une adresse donnée. */
  private final int maxPerAddress;

  /** Ce qui est compté pour une adresse vue comme source. */
  static final class Source {
    private final Map<String, Long> destinations = new HashMap<>();
    private final Map<String, Set<Integer>> ports = new HashMap<>();
    private long flows;
    private long bytes;
    private boolean saturated;
  }

  /** Ce qui est compté pour une adresse vue comme destination. */
  static final class Destination {
    private final Map<String, Long> sources = new HashMap<>();
    private long flows;
    private boolean saturated;
  }

  private static final class PerEquipment {
    private final Map<String, Source> sources = new HashMap<>();
    private final Map<String, Destination> destinations = new HashMap<>();
  }

  private final Map<UUID, PerEquipment> byEquipment = new ConcurrentHashMap<>();

  public FlowObservations(int maxTracked, int maxPerAddress) {
    this.maxTracked = Math.max(50, maxTracked);
    this.maxPerAddress = Math.max(50, maxPerAddress);
  }

  /** Compte un flux. Appelé depuis le thread du socket, un seul par récepteur. */
  public void add(UUID equipmentId, FlowRecord flow) {
    if (equipmentId == null || flow == null || !flow.isUsable()) {
      return;
    }
    PerEquipment state = byEquipment.computeIfAbsent(equipmentId, key -> new PerEquipment());
    synchronized (state) {
      addSource(state, flow);
      addDestination(state, flow);
    }
  }

  private void addSource(PerEquipment state, FlowRecord flow) {
    Source source = state.sources.get(flow.sourceAddress());
    if (source == null) {
      if (state.sources.size() >= maxTracked) {
        return;
      }
      source = new Source();
      state.sources.put(flow.sourceAddress(), source);
    }
    source.flows++;
    source.bytes += flow.bytes();
    if (source.destinations.size() < maxPerAddress
        || source.destinations.containsKey(flow.destinationAddress())) {
      source.destinations.merge(flow.destinationAddress(), 1L, Long::sum);
    } else {
      source.saturated = true;
    }
    Set<Integer> ports = source.ports.get(flow.destinationAddress());
    if (ports == null) {
      if (source.ports.size() < maxPerAddress) {
        ports = new HashSet<>();
        source.ports.put(flow.destinationAddress(), ports);
      } else {
        source.saturated = true;
        return;
      }
    }
    if (ports.size() < maxPerAddress) {
      ports.add(flow.destinationPort());
    } else {
      source.saturated = true;
    }
  }

  private void addDestination(PerEquipment state, FlowRecord flow) {
    Destination destination = state.destinations.get(flow.destinationAddress());
    if (destination == null) {
      if (state.destinations.size() >= maxTracked) {
        return;
      }
      destination = new Destination();
      state.destinations.put(flow.destinationAddress(), destination);
    }
    destination.flows++;
    if (destination.sources.size() < maxPerAddress
        || destination.sources.containsKey(flow.sourceAddress())) {
      destination.sources.merge(flow.sourceAddress(), 1L, Long::sum);
    } else {
      destination.saturated = true;
    }
  }

  /** Ce qui a été observé pour un équipement, sous la forme que le détecteur attend. */
  public record Views(
      List<ScanDetector.SourceView> sources, List<ScanDetector.DestinationView> destinations) {}

  /** Prend tout ce qui a été observé et repart de zéro. */
  public Map<UUID, Views> drain() {
    var taken = new HashMap<>(byEquipment);
    byEquipment.keySet().removeAll(taken.keySet());
    var result = new HashMap<UUID, Views>();
    taken.forEach((equipmentId, state) -> result.put(equipmentId, views(state)));
    return Map.copyOf(result);
  }

  private Views views(PerEquipment state) {
    var sources = new ArrayList<ScanDetector.SourceView>(state.sources.size());
    state.sources.forEach(
        (address, source) -> {
          var portCounts = new HashMap<String, Integer>();
          source.ports.forEach((destination, ports) -> portCounts.put(destination, ports.size()));
          sources.add(
              new ScanDetector.SourceView(
                  address,
                  Map.copyOf(source.destinations),
                  Map.copyOf(portCounts),
                  source.flows,
                  source.bytes,
                  source.saturated));
        });
    var destinations = new ArrayList<ScanDetector.DestinationView>(state.destinations.size());
    state.destinations.forEach(
        (address, destination) ->
            destinations.add(
                new ScanDetector.DestinationView(
                    address,
                    Map.copyOf(destination.sources),
                    destination.flows,
                    destination.saturated)));
    return new Views(List.copyOf(sources), List.copyOf(destinations));
  }

  /** Adresses suivies en ce moment, toutes natures confondues : pour surveiller la mémoire. */
  public int size() {
    int total = 0;
    for (PerEquipment state : byEquipment.values()) {
      synchronized (state) {
        total += state.sources.size() + state.destinations.size();
      }
    }
    return total;
  }
}

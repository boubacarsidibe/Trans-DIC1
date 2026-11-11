package sn.ept.transdic1.flow.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import sn.ept.transdic1.flow.receiver.FlowRecord;

/**
 * Comptage des flux en mémoire, entre le socket et la base (ADR 0032).
 *
 * <p>Un routeur d'école exporte des milliers de flux par minute. Les écrire un par un serait
 * absurde : la base enflerait pour rien et personne ne lit un flux isolé. Ils sont donc
 * <b>additionnés en mémoire</b> par conversation — qui parle à qui, par quel protocole et vers quel
 * port — puis vidés en base une fois par minute.
 *
 * <p>Deux compteurs séparés, et c'est important : le <b>total par équipement</b> est toujours
 * juste, même quand le détail est plafonné. Un parc qui déborde doit encore pouvoir dire combien
 * d'octets sont passés, quitte à ne plus savoir entre qui et qui.
 *
 * <p>Tout est appelé depuis le thread du socket : les structures sont concurrentes et aucune
 * opération ne prend de verrou long ni ne touche la base.
 */
public class FlowAccumulator {
  /**
   * Conversations distinctes retenues en même temps, toutes sources confondues. Au-delà, seuls les
   * totaux continuent d'être comptés : une tempête de balayage ne doit pas faire enfler la mémoire
   * du serveur de supervision.
   */
  private final int maxConversations;

  /** Une conversation : ce qui distingue deux lignes dans le détail. */
  public record Conversation(
      UUID equipmentId,
      String sourceAddress,
      String destinationAddress,
      int protocol,
      int destinationPort) {}

  /** Ce qui a été compté pour une conversation ou un équipement. */
  public static final class Counters {
    private final AtomicLong bytes = new AtomicLong();
    private final AtomicLong packets = new AtomicLong();
    private final AtomicLong flows = new AtomicLong();

    void add(long addedBytes, long addedPackets) {
      bytes.addAndGet(addedBytes);
      packets.addAndGet(addedPackets);
      flows.incrementAndGet();
    }

    public long bytes() {
      return bytes.get();
    }

    public long packets() {
      return packets.get();
    }

    public long flows() {
      return flows.get();
    }
  }

  /** Ce qu'un équipement a vu passer dans la minute. */
  public record EquipmentTotals(
      long bytes, long packets, long flows, int conversations, boolean truncated) {}

  /** Un instantané pris au moment du vidage : plus rien ne bouge dedans. */
  public record Snapshot(
      Map<UUID, EquipmentTotals> totals, Map<Conversation, Counters> conversations) {}

  private final Map<Conversation, Counters> conversations = new ConcurrentHashMap<>();
  private final Map<UUID, Counters> totals = new ConcurrentHashMap<>();
  private final Map<UUID, Boolean> truncated = new ConcurrentHashMap<>();

  public FlowAccumulator(int maxConversations) {
    this.maxConversations = Math.max(1_000, maxConversations);
  }

  /** Compte un flux au nom de l'équipement qui l'a exporté. */
  public void add(UUID equipmentId, FlowRecord flow) {
    if (equipmentId == null || flow == null || !flow.isUsable()) {
      return;
    }
    totals.computeIfAbsent(equipmentId, k -> new Counters()).add(flow.bytes(), flow.packets());
    var key =
        new Conversation(
            equipmentId,
            flow.sourceAddress(),
            flow.destinationAddress(),
            flow.protocol(),
            flow.destinationPort());
    Counters counters = conversations.get(key);
    if (counters == null) {
      if (conversations.size() >= maxConversations) {
        // Plafond atteint : le total reste juste, le détail de cette conversation est perdu.
        truncated.put(equipmentId, Boolean.TRUE);
        return;
      }
      counters = conversations.computeIfAbsent(key, k -> new Counters());
    }
    counters.add(flow.bytes(), flow.packets());
  }

  /**
   * Prend tout ce qui a été compté et repart de zéro.
   *
   * <p>Un flux qui arrive pendant le vidage est compté dans la minute suivante : à une minute près
   * sur un agrégat de trafic, cela n'a pas de conséquence, et cela évite un verrou qui bloquerait
   * le thread du socket.
   */
  public Snapshot drain() {
    var takenConversations = new HashMap<>(conversations);
    conversations.keySet().removeAll(takenConversations.keySet());
    var takenTotals = new HashMap<>(totals);
    totals.keySet().removeAll(takenTotals.keySet());
    var takenTruncated = new HashMap<>(truncated);
    truncated.keySet().removeAll(takenTruncated.keySet());

    var counts = new HashMap<UUID, Integer>();
    takenConversations.keySet().forEach(key -> counts.merge(key.equipmentId(), 1, Integer::sum));

    var result = new HashMap<UUID, EquipmentTotals>();
    takenTotals.forEach(
        (equipmentId, counters) ->
            result.put(
                equipmentId,
                new EquipmentTotals(
                    counters.bytes(),
                    counters.packets(),
                    counters.flows(),
                    counts.getOrDefault(equipmentId, 0),
                    takenTruncated.containsKey(equipmentId))));
    return new Snapshot(Map.copyOf(result), Map.copyOf(takenConversations));
  }

  /**
   * Les {@code limit} plus grosses conversations d'un équipement, par octets. Au-delà, le détail
   * n'apprend plus rien : personne ne lit la trois-centième conversation d'une minute.
   */
  public static List<Map.Entry<Conversation, Counters>> top(
      Map<Conversation, Counters> conversations, UUID equipmentId, int limit) {
    var list = new ArrayList<Map.Entry<Conversation, Counters>>();
    conversations.forEach(
        (key, counters) -> {
          if (key.equipmentId().equals(equipmentId)) {
            list.add(Map.entry(key, counters));
          }
        });
    list.sort(
        Comparator.comparingLong((Map.Entry<Conversation, Counters> e) -> e.getValue().bytes())
            .reversed());
    return list.size() > limit ? List.copyOf(list.subList(0, limit)) : List.copyOf(list);
  }

  /** Conversations retenues en ce moment, pour la supervision de la supervision. */
  public int size() {
    return conversations.size();
  }
}

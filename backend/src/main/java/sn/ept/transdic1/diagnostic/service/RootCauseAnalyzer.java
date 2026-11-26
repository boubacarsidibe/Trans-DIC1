package sn.ept.transdic1.diagnostic.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Recherche de cause racine sur le graphe des pannes (ADR 0056), sans base ni Spring : le graphe
 * lui est fourni, ce qui la rend vérifiable sur des scénarios construits.
 *
 * <p>Depuis le point de départ (toujours tenu pour en panne), elle descend la chaîne — un service
 * vers les services dont il dépend et vers l'équipement où il tourne, un équipement vers ses
 * ressources saturées et, s'il est lui-même hors service, vers son amont (hôte d'une VM,
 * commutateur, routeur) — en ne suivant que ce qui est en panne au même moment. Chaque branche
 * s'arrête sur le nœud en panne le plus bas : ce sont les causes candidates. Un nœud déjà visité
 * n'est jamais repris (un cycle ne fait pas boucler), la profondeur et le nombre de nœuds sont
 * bornés.
 */
final class RootCauseAnalyzer {
  /** Ce qu'est un nœud du graphe. */
  enum Kind {
    SERVICE,
    EQUIPMENT,
    RESOURCE
  }

  /**
   * Un nœud tel que l'analyse le voit. {@code visible} faux : hors du périmètre de l'utilisateur,
   * l'analyse s'y arrête et n'en dit rien.
   */
  record Node(
      Kind kind,
      UUID id,
      String name,
      String detailType,
      UUID equipmentId,
      String equipmentName,
      boolean faulty,
      boolean down,
      String state,
      String family,
      List<String> evidence,
      boolean visible) {}

  /** Lien vers un nœud plus bas, avec sa relation lisible (« dépend de », « tourne sur »…). */
  record Edge(Node target, String relation) {}

  /** Le graphe, lu à la demande. */
  interface Graph {
    List<Edge> children(Node node);
  }

  /** Une étape d'un chemin : le nœud et sa relation avec l'étape précédente. */
  record Step(Node node, String relation) {}

  /** Un chemin du point de départ à une cause candidate (la dernière étape). */
  record Path(List<Step> steps) {
    Node cause() {
      return steps.get(steps.size() - 1).node();
    }

    Node start() {
      return steps.get(0).node();
    }

    int depth() {
      return steps.size() - 1;
    }
  }

  private final int maxDepth;
  private final int maxNodes;

  RootCauseAnalyzer(int maxDepth, int maxNodes) {
    this.maxDepth = maxDepth;
    this.maxNodes = maxNodes;
  }

  /** Les chemins vers chaque cause candidate, dans l'ordre du parcours. */
  List<Path> analyze(Graph graph, Node start) {
    List<Path> paths = new ArrayList<>();
    Set<String> visited = new HashSet<>();
    visited.add(key(start));
    explore(graph, new ArrayList<>(List.of(new Step(start, null))), visited, paths);
    return paths;
  }

  private void explore(Graph graph, List<Step> path, Set<String> visited, List<Path> out) {
    Node node = path.get(path.size() - 1).node();
    List<Edge> next = new ArrayList<>();
    if (node.visible() && path.size() <= maxDepth) {
      for (Edge edge : graph.children(node)) {
        Node target = edge.target();
        if (target.faulty() && visited.size() < maxNodes && visited.add(key(target))) {
          next.add(edge);
        }
      }
    }
    if (next.isEmpty()) {
      out.add(new Path(List.copyOf(path)));
      return;
    }
    for (Edge edge : next) {
      path.add(new Step(edge.target(), edge.relation()));
      explore(graph, path, visited, out);
      path.remove(path.size() - 1);
    }
  }

  private static String key(Node node) {
    return node.kind() + ":" + node.id() + (node.family() == null ? "" : ":" + node.family());
  }
}

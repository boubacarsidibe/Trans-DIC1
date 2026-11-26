package sn.ept.transdic1.diagnostic.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import sn.ept.transdic1.common.scope.SiteScope;
import sn.ept.transdic1.diagnostic.domain.CauseSource;
import sn.ept.transdic1.diagnostic.dto.RootCauseResponse;
import sn.ept.transdic1.diagnostic.dto.RootCauseResponse.Chemin;
import sn.ept.transdic1.diagnostic.dto.RootCauseResponse.Etape;
import sn.ept.transdic1.diagnostic.repository.RootCauseRepository;
import sn.ept.transdic1.diagnostic.repository.RootCauseRepository.AlertFact;
import sn.ept.transdic1.diagnostic.repository.RootCauseRepository.EquipmentFact;
import sn.ept.transdic1.diagnostic.repository.RootCauseRepository.ServiceFact;
import sn.ept.transdic1.diagnostic.repository.RootCauseRepository.Subject;
import sn.ept.transdic1.diagnostic.service.RootCauseAnalyzer.Edge;
import sn.ept.transdic1.diagnostic.service.RootCauseAnalyzer.Kind;
import sn.ept.transdic1.diagnostic.service.RootCauseAnalyzer.Node;
import sn.ept.transdic1.diagnostic.service.RootCauseAnalyzer.Path;

/**
 * Cause racine d'une alerte ou d'un incident (ADR 0056) : charge le graphe (services, dépendances,
 * équipements, amont) au moment de l'ouverture, le fait parcourir par {@link RootCauseAnalyzer},
 * classe les causes candidates avec les poids du diagnostic (appris des confirmations, ADR 0045) et
 * rédige la conclusion.
 *
 * <p>Lecture seule, même périmètre que la lecture de l'alerte : un élément hors périmètre arrête la
 * descente et n'est jamais nommé.
 */
@Service
public class RootCauseService {
  static final int MAX_CAUSES = 5;

  private final RootCauseRepository repository;
  private final CauseWeightService weights;
  private final SiteScope scope;
  private final Duration window;
  private final RootCauseAnalyzer analyzer;

  public RootCauseService(
      RootCauseRepository repository,
      CauseWeightService weights,
      SiteScope scope,
      @Value("${diagnostic.root-cause.window-minutes:15}") long windowMinutes,
      @Value("${diagnostic.root-cause.max-depth:12}") int maxDepth,
      @Value("${diagnostic.root-cause.max-nodes:200}") int maxNodes) {
    this.repository = repository;
    this.weights = weights;
    this.scope = scope;
    this.window = Duration.ofMinutes(Math.max(1, windowMinutes));
    this.analyzer = new RootCauseAnalyzer(Math.max(1, maxDepth), Math.max(2, maxNodes));
  }

  @Transactional(readOnly = true)
  public RootCauseResponse forAlert(UUID alertId) {
    Subject subject =
        repository
            .alert(alertId)
            .filter(s -> scope.allows(s.siteId()))
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Alerte introuvable"));
    return analyze("ALERTE", alertId, subject);
  }

  @Transactional(readOnly = true)
  public RootCauseResponse forIncident(UUID incidentId) {
    if (!repository.incidentExists(incidentId)
        || !scope.allows(repository.incidentSite(incidentId).orElse(null))) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Incident introuvable");
    }
    Subject subject =
        repository
            .incidentRootAlert(incidentId)
            .flatMap(repository::alert)
            .filter(s -> scope.allows(s.siteId()))
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Incident introuvable"));
    return analyze("INCIDENT", incidentId, subject);
  }

  private RootCauseResponse analyze(String kind, UUID subjectId, Subject subject) {
    Instant from = subject.openedAt().minus(window);
    Instant to = subject.openedAt().plus(window);
    var graph = new LoadedGraph(from, to);
    Node start =
        subject.serviceId() != null
            ? graph.serviceNode(subject.serviceId(), true, subject)
            : graph.equipmentNode(subject.equipmentId(), true, subject);
    if (start == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Alerte introuvable");
    }
    var snapshot = weights.current();
    List<Ranked> ranked = new ArrayList<>();
    for (Path path : analyzer.analyze(graph, start)) {
      CauseSource source = source(path);
      double weight =
          snapshot.weights().getOrDefault(source, new CauseScoring.Weight(0.5, 0)).weight();
      ranked.add(new Ranked(path, source, weight));
    }
    // Le poids d'abord (appris des confirmations), puis la profondeur : à poids égal, la cause la
    // plus basse est la plus probable.
    ranked.sort(
        Comparator.comparingDouble(Ranked::weight)
            .reversed()
            .thenComparing(Comparator.comparingInt((Ranked r) -> r.path().depth()).reversed())
            .thenComparing(r -> r.path().cause().name() == null ? "" : r.path().cause().name()));
    List<Chemin> causes =
        ranked.stream()
            .limit(MAX_CAUSES)
            .map(
                r ->
                    new Chemin(
                        RootCauseTexts.conclusion(r.path(), subject),
                        r.source().name(),
                        round(r.weight()),
                        steps(r.path())))
            .toList();
    Chemin retained = causes.get(0);
    boolean found = ranked.get(0).path().depth() > 0;
    return new RootCauseResponse(
        kind,
        subjectId,
        subject.alertId(),
        found,
        retained.conclusion(),
        causes,
        (int) window.toMinutes(),
        snapshot.learned());
  }

  /** Origine d'une cause au sens du diagnostic, pour lui appliquer le poids correspondant. */
  static CauseSource source(Path path) {
    Node cause = path.cause();
    if (cause.kind() == Kind.RESOURCE) {
      return CauseSource.TENDANCE_SATURATION;
    }
    if (path.depth() == 0) {
      return CauseSource.SILENCE_COLLECTE;
    }
    return CauseSource.AMONT_EN_ALERTE;
  }

  private static List<Etape> steps(Path path) {
    List<Etape> list = new ArrayList<>();
    for (var step : path.steps()) {
      Node n = step.node();
      if (!n.visible()) {
        list.add(
            new Etape(
                "HORS_PERIMETRE",
                null,
                "élément hors de votre périmètre",
                step.relation(),
                "en panne",
                true,
                List.of()));
        continue;
      }
      String type =
          switch (n.kind()) {
            case SERVICE -> "SERVICE";
            case EQUIPMENT -> "EQUIPEMENT";
            case RESOURCE -> "RESSOURCE";
          };
      list.add(
          new Etape(type, n.id(), n.name(), step.relation(), n.state(), n.faulty(), n.evidence()));
    }
    return list;
  }

  private static double round(double value) {
    return Math.round(value * 1000.0) / 1000.0;
  }

  private record Ranked(Path path, CauseSource source, double weight) {}

  /** Graphe lu à la demande dans la base, chaque nœud une seule fois. */
  private final class LoadedGraph implements RootCauseAnalyzer.Graph {
    private final Instant from;
    private final Instant to;
    private final Map<UUID, EquipmentFact> equipments = new HashMap<>();
    private final Map<UUID, ServiceFact> services = new HashMap<>();

    LoadedGraph(Instant from, Instant to) {
      this.from = from;
      this.to = to;
    }

    private EquipmentFact equipment(UUID id) {
      return equipments.computeIfAbsent(id, k -> repository.equipment(k, from, to).orElse(null));
    }

    private ServiceFact service(UUID id) {
      return services.computeIfAbsent(id, k -> repository.service(k, from, to).orElse(null));
    }

    Node equipmentNode(UUID id, boolean start, Subject subject) {
      EquipmentFact e = equipment(id);
      if (e == null) {
        return null;
      }
      boolean down = e.collection() != null || (start && "COLLECTION".equals(subject.type()));
      boolean faulty = start || down || !e.resources().isEmpty();
      List<String> evidence = new ArrayList<>();
      if (e.collection() != null) {
        evidence.add(RootCauseTexts.evidence(e.collection()));
      } else if (start) {
        evidence.add(RootCauseTexts.clean(subject.message()));
      }
      if (e.power() != null && e.power() == 0) {
        evidence.add("L'hyperviseur la voit éteinte (hv.power = 0)");
      }
      String state =
          down
              ? (e.power() != null && e.power() == 0 ? "éteinte" : "hors service")
              : (start ? "en alerte" : "en service");
      return new Node(
          Kind.EQUIPMENT,
          e.id(),
          e.name(),
          e.type(),
          e.id(),
          e.name(),
          faulty,
          down,
          state,
          null,
          List.copyOf(evidence),
          scope.allows(e.siteId()));
    }

    Node serviceNode(UUID id, boolean start, Subject subject) {
      ServiceFact s = service(id);
      if (s == null) {
        return null;
      }
      boolean stopped = s.lastUp() != null && s.lastUp() == 0;
      boolean faulty = start || stopped || !s.alerts().isEmpty();
      List<String> evidence = new ArrayList<>();
      s.alerts().forEach(a -> evidence.add(RootCauseTexts.evidence(a)));
      if (stopped) {
        evidence.add(
            "AGENT_SERVICE".equals(s.type())
                ? "Dernière mesure de l'agent : service.up[" + s.name() + "] = 0"
                : "Dernière mesure de la sonde : probe.up = 0");
      }
      if (start && evidence.isEmpty()) {
        evidence.add(RootCauseTexts.clean(subject.message()));
      }
      EquipmentFact host = equipment(s.equipmentId());
      String state =
          !faulty ? "en service" : "AGENT_SERVICE".equals(s.type()) ? "arrêté" : "ne répond plus";
      return new Node(
          Kind.SERVICE,
          s.id(),
          s.name(),
          s.type(),
          s.equipmentId(),
          host == null ? null : host.name(),
          faulty,
          faulty,
          state,
          null,
          List.copyOf(evidence),
          scope.allows(s.siteId()));
    }

    @Override
    public List<Edge> children(Node node) {
      List<Edge> edges = new ArrayList<>();
      switch (node.kind()) {
        case SERVICE -> {
          ServiceFact s = service(node.id());
          if (s == null) return edges;
          for (UUID dependency : s.dependsOn()) {
            Node target = serviceNode(dependency, false, null);
            if (target != null) edges.add(new Edge(target, "dépend de"));
          }
          Node host = equipmentNode(s.equipmentId(), false, null);
          if (host != null) edges.add(new Edge(host, "tourne sur"));
        }
        case EQUIPMENT -> {
          EquipmentFact e = equipment(node.id());
          if (e == null) return edges;
          for (AlertFact resource : e.resources()) {
            edges.add(new Edge(resourceNode(e, resource), "sature"));
          }
          // L'amont n'explique une panne que si l'équipement lui-même est injoignable.
          if (node.down()) {
            for (UUID upstream : e.upstreams()) {
              Node target = equipmentNode(upstream, false, null);
              if (target != null) {
                boolean hosting =
                    "VIRTUAL_MACHINE".equals(e.type()) && "SERVER".equals(target.detailType());
                edges.add(new Edge(target, hosting ? "hébergée par" : "derrière"));
              }
            }
          }
        }
        case RESOURCE -> {
          // Une ressource saturée est une cause : rien plus bas.
        }
      }
      return edges;
    }

    private Node resourceNode(EquipmentFact e, AlertFact alert) {
      String state = RootCauseTexts.resourceState(alert.family());
      return new Node(
          Kind.RESOURCE,
          alert.id(),
          state + " sur " + e.name(),
          alert.family(),
          e.id(),
          e.name(),
          true,
          true,
          state,
          alert.family(),
          List.of(RootCauseTexts.evidence(alert)),
          scope.allows(e.siteId()));
    }
  }

  /** Rédaction des conclusions, à part pour être lue et testée seule. */
  static final class RootCauseTexts {
    private RootCauseTexts() {}

    static String clean(String text) {
      if (text == null) return "";
      String value = text.replaceAll("\\p{Cntrl}", " ").strip();
      return value.length() > 200 ? value.substring(0, 200) + "…" : value;
    }

    static String evidence(AlertFact alert) {
      return "Alerte du "
          + alert.triggeredAt().toString().substring(0, 16).replace('T', ' ')
          + " UTC : "
          + clean(alert.message());
    }

    static String resourceState(String family) {
      return switch (family == null ? "" : family) {
        case "disk" -> "disque plein";
        case "memory" -> "mémoire saturée";
        case "cpu" -> "processeur saturé";
        default -> "ressource saturée";
      };
    }

    /** « le routeur rtr-coeur », « la VM vm-web-01 »… */
    static String label(Node node) {
      String article =
          switch (node.detailType() == null ? "" : node.detailType()) {
            case "ROUTER" -> "le routeur";
            case "SWITCH" -> "le commutateur";
            case "VIRTUAL_MACHINE" -> "la VM";
            case "WIFI_ACCESS_POINT" -> "la borne Wi-Fi";
            default -> "le serveur";
          };
      return article + " " + node.name();
    }

    private static String capital(String text) {
      return text.isEmpty()
          ? text
          : text.substring(0, 1).toUpperCase(Locale.ROOT) + text.substring(1);
    }

    /** Sujet de la phrase : le service par son nom, l'équipement par son article et son nom. */
    private static String subject(Node start) {
      return start.kind() == Kind.SERVICE ? start.name() : capital(label(start));
    }

    static String conclusion(Path path, Subject subject) {
      Node start = path.start();
      Node cause = path.cause();
      if (!start.visible()) {
        return "La cause se trouve hors de votre périmètre.";
      }
      if (!cause.visible()) {
        return subject(start) + " est en panne à cause d'un élément hors de votre périmètre.";
      }
      if (path.depth() == 0) {
        if (start.kind() == Kind.SERVICE) {
          String where = start.equipmentName() == null ? "" : " sur " + start.equipmentName();
          return "AGENT_SERVICE".equals(start.detailType())
              ? start.name() + " est arrêté" + where
              : start.name()
                  + " ne répond plus"
                  + where
                  + " ; aucune panne plus profonde au même moment";
        }
        if (start.down()) {
          return subject(start)
              + " est "
              + start.state()
              + " ; aucune panne plus profonde au même moment";
        }
        return subject(start) + " : " + clean(subject.message());
      }
      switch (cause.kind()) {
        case RESOURCE -> {
          if (start.kind() == Kind.EQUIPMENT && cause.equipmentId().equals(start.id())) {
            return subject(start) + " : " + cause.state();
          }
          return subject(start)
              + " ne répond plus : "
              + cause.state()
              + " sur "
              + cause.equipmentName();
        }
        case EQUIPMENT -> {
          String causeText =
              "VIRTUAL_MACHINE".equals(cause.detailType()) && "éteinte".equals(cause.state())
                  ? label(cause) + " est éteinte"
                  : label(cause) + " est hors service";
          if (start.kind() == Kind.EQUIPMENT) {
            boolean hostOfVm =
                path.depth() == 1
                    && "VIRTUAL_MACHINE".equals(start.detailType())
                    && "SERVER".equals(cause.detailType());
            String startState = "éteinte".equals(start.state()) ? "éteinte" : "injoignable";
            return hostOfVm
                ? subject(start)
                    + " est "
                    + startState
                    + " parce que son hôte "
                    + cause.name()
                    + " est hors service"
                : subject(start) + " est injoignable parce que " + causeText;
          }
          return subject(start) + " est injoignable parce que " + causeText;
        }
        default -> {
          if ("AGENT_SERVICE".equals(cause.detailType())) {
            String where = cause.equipmentName() == null ? "" : " sur " + cause.equipmentName();
            return subject(start) + " est en panne : " + cause.name() + " est arrêté" + where;
          }
          return subject(start)
              + " est en panne parce que "
              + cause.name()
              + ", dont il dépend, ne répond plus";
        }
      }
    }
  }
}

package sn.ept.transdic1.diagnostic.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sn.ept.transdic1.diagnostic.domain.CauseSource;
import sn.ept.transdic1.diagnostic.repository.RootCauseRepository.Subject;
import sn.ept.transdic1.diagnostic.service.RootCauseAnalyzer.Edge;
import sn.ept.transdic1.diagnostic.service.RootCauseAnalyzer.Kind;
import sn.ept.transdic1.diagnostic.service.RootCauseAnalyzer.Node;
import sn.ept.transdic1.diagnostic.service.RootCauseAnalyzer.Path;

/** Recherche de cause racine sur des graphes construits (ADR 0056), sans base. */
class RootCauseAnalyzerTests {
  private final RootCauseAnalyzer analyzer = new RootCauseAnalyzer(12, 200);
  private final Map<UUID, List<Edge>> edges = new HashMap<>();
  private final RootCauseAnalyzer.Graph graph = node -> edges.getOrDefault(node.id(), List.of());

  private static final Subject SUBJECT =
      new Subject(
          UUID.randomUUID(),
          null,
          null,
          null,
          "THRESHOLD",
          "Alerte de départ",
          java.time.Instant.EPOCH);

  private static Node service(String name, boolean faulty) {
    return new Node(
        Kind.SERVICE,
        UUID.randomUUID(),
        name,
        "HTTPS",
        null,
        "vm",
        faulty,
        faulty,
        faulty ? "ne répond plus" : "en service",
        null,
        List.of(),
        true);
  }

  private static Node agentService(String name, String host, boolean faulty) {
    return new Node(
        Kind.SERVICE,
        UUID.randomUUID(),
        name,
        "AGENT_SERVICE",
        null,
        host,
        faulty,
        faulty,
        faulty ? "arrêté" : "en service",
        null,
        List.of(),
        true);
  }

  private static Node equipment(String name, String type, boolean down) {
    return new Node(
        Kind.EQUIPMENT,
        UUID.randomUUID(),
        name,
        type,
        null,
        name,
        down,
        down,
        down ? "hors service" : "en service",
        null,
        List.of(),
        true);
  }

  private static Node hidden(String type) {
    return new Node(
        Kind.EQUIPMENT,
        UUID.randomUUID(),
        "secret",
        type,
        null,
        "secret",
        true,
        true,
        "hors service",
        null,
        List.of(),
        false);
  }

  private static Node disk(Node on) {
    return new Node(
        Kind.RESOURCE,
        UUID.randomUUID(),
        "disque plein sur " + on.name(),
        "disk",
        on.id(),
        on.name(),
        true,
        true,
        "disque plein",
        "disk",
        List.of(),
        true);
  }

  private void link(Node from, Node to, String relation) {
    edges.computeIfAbsent(from.id(), k -> new ArrayList<>()).add(new Edge(to, relation));
  }

  private List<String> causes(Node start) {
    return analyzer.analyze(graph, start).stream().map(p -> p.cause().name()).toList();
  }

  @Test
  @DisplayName("Chaîne complète : service, VM, hôte ; la cause est l'hôte, pas le commutateur sain")
  void fullChainStopsAtTheLowestFaultyNode() {
    var ent = service("ENT", true);
    var db = service("PostgreSQL", false);
    var vm = equipment("vm-ent", "VIRTUAL_MACHINE", true);
    var host = equipment("proxmox-02", "SERVER", true);
    var sw = equipment("sw-coeur", "SWITCH", false);
    link(ent, db, "dépend de");
    link(ent, vm, "tourne sur");
    link(vm, host, "hébergée par");
    link(host, sw, "derrière");

    List<Path> paths = analyzer.analyze(graph, ent);

    assertThat(paths).hasSize(1);
    assertThat(paths.get(0).steps())
        .extracting(s -> s.node().name())
        .containsExactly("ENT", "vm-ent", "proxmox-02");
    assertThat(paths.get(0).steps())
        .extracting(RootCauseAnalyzer.Step::relation)
        .containsExactly(null, "tourne sur", "hébergée par");
    assertThat(RootCauseService.RootCauseTexts.conclusion(paths.get(0), SUBJECT))
        .isEqualTo("ENT est injoignable parce que le serveur proxmox-02 est hors service");
  }

  @Test
  @DisplayName("L'ENT est injoignable parce que le routeur de cœur est hors service")
  void networkCause() {
    var ent = service("ENT", true);
    var vm = equipment("vm-ent", "VIRTUAL_MACHINE", true);
    var router = equipment("rtr-coeur", "ROUTER", true);
    link(ent, vm, "tourne sur");
    link(vm, router, "derrière");

    Path path = analyzer.analyze(graph, ent).get(0);

    assertThat(RootCauseService.RootCauseTexts.conclusion(path, SUBJECT))
        .isEqualTo("ENT est injoignable parce que le routeur rtr-coeur est hors service");
    assertThat(RootCauseService.source(path)).isEqualTo(CauseSource.AMONT_EN_ALERTE);
  }

  @Test
  @DisplayName("La VM est éteinte parce que son hôte est hors service")
  void vmBecauseOfItsHost() {
    var vm =
        new Node(
            Kind.EQUIPMENT,
            UUID.randomUUID(),
            "vm-web-01",
            "VIRTUAL_MACHINE",
            null,
            "vm-web-01",
            true,
            true,
            "éteinte",
            null,
            List.of(),
            true);
    var host = equipment("proxmox-02", "SERVER", true);
    link(vm, host, "hébergée par");

    assertThat(
            RootCauseService.RootCauseTexts.conclusion(analyzer.analyze(graph, vm).get(0), SUBJECT))
        .isEqualTo("La VM vm-web-01 est éteinte parce que son hôte proxmox-02 est hors service");
  }

  @Test
  @DisplayName("nginx est arrêté sur vm-web-01 : la VM saine n'est pas une cause")
  void stoppedServiceOnAHealthyVm() {
    var nginx = agentService("nginx", "vm-web-01", true);
    link(nginx, equipment("vm-web-01", "VIRTUAL_MACHINE", false), "tourne sur");

    List<Path> paths = analyzer.analyze(graph, nginx);

    assertThat(paths).hasSize(1);
    assertThat(paths.get(0).depth()).isZero();
    assertThat(RootCauseService.RootCauseTexts.conclusion(paths.get(0), SUBJECT))
        .isEqualTo("nginx est arrêté sur vm-web-01");
    assertThat(RootCauseService.source(paths.get(0))).isEqualTo(CauseSource.SILENCE_COLLECTE);
  }

  @Test
  @DisplayName("La base ne répond plus : disque plein sur la VM qui l'héberge")
  void diskFull() {
    var postgres = service("PostgreSQL", true);
    var vm =
        new Node(
            Kind.EQUIPMENT,
            UUID.randomUUID(),
            "vm-db-01",
            "VIRTUAL_MACHINE",
            null,
            "vm-db-01",
            true,
            false,
            "en service",
            null,
            List.of(),
            true);
    link(postgres, vm, "tourne sur");
    link(vm, disk(vm), "sature");

    Path path = analyzer.analyze(graph, postgres).get(0);

    assertThat(RootCauseService.RootCauseTexts.conclusion(path, SUBJECT))
        .isEqualTo("PostgreSQL ne répond plus : disque plein sur vm-db-01");
    assertThat(RootCauseService.source(path)).isEqualTo(CauseSource.TENDANCE_SATURATION);
  }

  @Test
  @DisplayName("Une dépendance arrêtée est la cause d'un service en panne")
  void dependencyCauses() {
    var ent = service("ENT", true);
    var ldap = agentService("slapd", "srv-ldap", true);
    link(ent, ldap, "dépend de");
    assertThat(
            RootCauseService.RootCauseTexts.conclusion(
                analyzer.analyze(graph, ent).get(0), SUBJECT))
        .isEqualTo("ENT est en panne : slapd est arrêté sur srv-ldap");
    var app = service("Appli", true);
    var api = service("API", true);
    link(app, api, "dépend de");
    assertThat(
            RootCauseService.RootCauseTexts.conclusion(
                analyzer.analyze(graph, app).get(0), SUBJECT))
        .isEqualTo("Appli est en panne parce que API, dont il dépend, ne répond plus");
  }

  @Test
  @DisplayName("Plusieurs causes simultanées : un chemin par cause")
  void severalSimultaneousCauses() {
    var ent = service("ENT", true);
    link(ent, service("PostgreSQL", true), "dépend de");
    link(ent, service("LDAP", true), "dépend de");
    assertThat(causes(ent)).containsExactly("PostgreSQL", "LDAP");
  }

  @Test
  @DisplayName("Un cycle ne fait pas boucler : chaque nœud n'est visité qu'une fois")
  void cycleIsHarmless() {
    var a = service("A", true);
    var b = service("B", true);
    link(a, b, "dépend de");
    link(b, a, "dépend de");
    assertThat(causes(a)).containsExactly("B");
  }

  @Test
  @DisplayName("Un élément hors périmètre arrête la descente et n'est jamais nommé")
  void hiddenNodeStopsTheSearch() {
    var vm = equipment("vm-a", "VIRTUAL_MACHINE", true);
    var secret = hidden("ROUTER");
    link(vm, secret, "derrière");
    link(secret, equipment("plus-bas", "ROUTER", true), "derrière");

    Path path = analyzer.analyze(graph, vm).get(0);

    assertThat(path.cause()).isEqualTo(secret);
    assertThat(RootCauseService.RootCauseTexts.conclusion(path, SUBJECT))
        .isEqualTo("La VM vm-a est en panne à cause d'un élément hors de votre périmètre.")
        .doesNotContain("secret");
  }

  @Test
  @DisplayName("Profondeur bornée")
  void depthIsBounded() {
    var shallow = new RootCauseAnalyzer(2, 200);
    var a = equipment("a", "SERVER", true);
    var b = equipment("b", "SWITCH", true);
    var c = equipment("c", "SWITCH", true);
    var d = equipment("d", "ROUTER", true);
    link(a, b, "derrière");
    link(b, c, "derrière");
    link(c, d, "derrière");
    assertThat(shallow.analyze(graph, a).get(0).cause().name()).isEqualTo("c");
  }

  @Test
  @DisplayName("Sans cause plus basse : le point de départ, dit tel quel")
  void startAlone() {
    var server = equipment("srv-01", "SERVER", true);
    assertThat(
            RootCauseService.RootCauseTexts.conclusion(
                analyzer.analyze(graph, server).get(0), SUBJECT))
        .isEqualTo(
            "Le serveur srv-01 est hors service ; aucune panne plus profonde au même moment");
    var healthy =
        new Node(
            Kind.EQUIPMENT,
            UUID.randomUUID(),
            "srv-02",
            "SERVER",
            null,
            "srv-02",
            true,
            false,
            "en alerte",
            null,
            List.of(),
            true);
    assertThat(
            RootCauseService.RootCauseTexts.conclusion(
                analyzer.analyze(graph, healthy).get(0), SUBJECT))
        .isEqualTo("Le serveur srv-02 : Alerte de départ");
    var probe = service("Portail", true);
    assertThat(
            RootCauseService.RootCauseTexts.conclusion(
                analyzer.analyze(graph, probe).get(0), SUBJECT))
        .isEqualTo("Portail ne répond plus sur vm ; aucune panne plus profonde au même moment");
  }

  @Test
  void textsHelpers() {
    assertThat(RootCauseService.RootCauseTexts.resourceState("memory"))
        .isEqualTo("mémoire saturée");
    assertThat(RootCauseService.RootCauseTexts.resourceState("cpu")).isEqualTo("processeur saturé");
    assertThat(RootCauseService.RootCauseTexts.resourceState(null)).isEqualTo("ressource saturée");
    assertThat(RootCauseService.RootCauseTexts.clean("a\nb")).isEqualTo("a b");
    assertThat(RootCauseService.RootCauseTexts.clean("x".repeat(300))).hasSize(201);
    assertThat(RootCauseService.RootCauseTexts.label(equipment("ap", "WIFI_ACCESS_POINT", true)))
        .isEqualTo("la borne Wi-Fi ap");
  }
}

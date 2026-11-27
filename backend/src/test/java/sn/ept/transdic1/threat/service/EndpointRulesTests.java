package sn.ept.transdic1.threat.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sn.ept.transdic1.alerting.domain.Severity;
import sn.ept.transdic1.threat.domain.EndpointBaselineItem;
import sn.ept.transdic1.threat.dto.EndpointReport;
import sn.ept.transdic1.threat.dto.EndpointReport.Account;
import sn.ept.transdic1.threat.dto.EndpointReport.Connection;
import sn.ept.transdic1.threat.dto.EndpointReport.Integrity;
import sn.ept.transdic1.threat.dto.EndpointReport.Persistence;
import sn.ept.transdic1.threat.dto.EndpointReport.Process;
import sn.ept.transdic1.threat.service.EndpointRules.Draft;

/** Règles de l'EDR léger (ADR 0058), sur des rapports écrits à la main. */
class EndpointRulesTests {
  private static final String HASH_A = "a".repeat(64);
  private static final String HASH_B = "b".repeat(64);

  private static EndpointReport report(
      List<Process> processes,
      List<Persistence> persistence,
      List<Account> accounts,
      List<Integrity> integrity,
      List<Connection> connections) {
    return new EndpointReport(
        "Linux", false, processes, persistence, accounts, integrity, connections);
  }

  private static EndpointReport clean() {
    return report(
        List.of(new Process("nginx", "/usr/sbin/nginx"), new Process("kworker", null)),
        List.of(new Persistence("cron", "/etc/crontab", HASH_A, null)),
        List.of(new Account("root", true, true), new Account("awa", true, false)),
        List.of(
            new Integrity("/etc/hosts", true, HASH_A),
            new Integrity("/etc/ld.so.preload", false, null)),
        List.of(new Connection("203.0.113.9", 443)));
  }

  private static List<String> rules(List<Draft> drafts) {
    return drafts.stream().map(Draft::rule).toList();
  }

  @Test
  @DisplayName("Un poste sain ne produit aucun constat, ni au premier rapport ni ensuite")
  void cleanMachine() {
    assertThat(EndpointRules.evaluate(clean(), null)).isEmpty();
    assertThat(EndpointRules.evaluate(clean(), EndpointRules.baselineOf(clean()))).isEmpty();
  }

  @Test
  @DisplayName(
      "Indicateurs : dossier temporaire, outil offensif, ld.so.preload, UID 0 caché, port suspect")
  void indicators() {
    var drafts =
        EndpointRules.evaluate(
            report(
                List.of(
                    new Process("xmrig", "/tmp/.x/xmrig"),
                    new Process("mimikatz.exe", "C:\\Users\\Public\\m.exe"),
                    new Process(
                        "updater.exe", "C:\\Users\\awa\\AppData\\Local\\Temp\\updater.exe")),
                List.of(),
                List.of(new Account("root", true, true), new Account("toor", true, true)),
                List.of(new Integrity("/etc/ld.so.preload", true, HASH_A)),
                List.of(new Connection("198.51.100.7", 4444))),
            null);
    assertThat(rules(drafts))
        .contains(
            "PROCESS_TEMP_PATH",
            "PROCESS_OFFENSIVE_TOOL",
            "LD_PRELOAD",
            "HIDDEN_SUPERUSER",
            "SUSPICIOUS_PORT");
    Draft miner =
        drafts.stream()
            .filter(d -> d.rule().equals("PROCESS_OFFENSIVE_TOOL") && d.subject().contains("xmrig"))
            .findFirst()
            .orElseThrow();
    assertThat(miner.technique().id()).isEqualTo("T1496");
    assertThat(miner.severity()).isEqualTo(Severity.CRITICAL);
    // Un outil renommé n'est pas reconnu par son nom de processus, mais par son chemin sous
    // Windows.
    assertThat(drafts)
        .filteredOn(d -> d.rule().equals("PROCESS_OFFENSIVE_TOOL"))
        .extracting(Draft::subject)
        .containsExactly("/tmp/.x/xmrig");
    assertThat(drafts)
        .filteredOn(d -> d.rule().equals("PROCESS_TEMP_PATH"))
        .extracting(Draft::subject)
        .containsExactlyInAnyOrder(
            "/tmp/.x/xmrig",
            "C:\\Users\\Public\\m.exe",
            "C:\\Users\\awa\\AppData\\Local\\Temp\\updater.exe");
    assertThat(drafts)
        .filteredOn(d -> d.rule().equals("HIDDEN_SUPERUSER"))
        .extracting(Draft::subject)
        .containsExactly("toor");
  }

  @Test
  @DisplayName("Changements : rien au premier rapport, puis persistance, comptes et fichiers")
  void changesAfterBaseline() {
    List<EndpointBaselineItem> baseline = EndpointRules.baselineOf(clean());
    var changed =
        report(
            List.of(),
            List.of(
                new Persistence("cron", "/etc/crontab", HASH_B, null),
                new Persistence("cron", "/etc/cron.d/x", HASH_A, null),
                new Persistence("run-key", "HKCU\\Run\\upd", HASH_A, "C:\\Temp\\upd.exe"),
                new Persistence("service", "EvilSvc", null, null)),
            List.of(
                new Account("root", true, true),
                new Account("awa", true, false),
                new Account("pirate", true, false),
                new Account("stagiaire", false, false)),
            List.of(
                new Integrity("/etc/hosts", true, HASH_B),
                new Integrity("/etc/ld.so.preload", false, null)),
            List.of());
    assertThat(EndpointRules.evaluate(changed, null))
        .as("le premier rapport fixe la référence")
        .isEmpty();
    var drafts = EndpointRules.evaluate(changed, baseline);
    assertThat(rules(drafts))
        .containsExactlyInAnyOrder(
            "CHANGED_PERSISTENCE",
            "NEW_PERSISTENCE",
            "NEW_PERSISTENCE",
            "NEW_PERSISTENCE",
            "NEW_PRIVILEGED_ACCOUNT",
            "NEW_ACCOUNT",
            "FILE_CHANGED");
    assertThat(drafts)
        .filteredOn(d -> d.rule().equals("NEW_PERSISTENCE") && d.subject().startsWith("run-key"))
        .singleElement()
        .satisfies(
            d -> {
              assertThat(d.technique().id()).isEqualTo("T1547.001");
              assertThat(d.message()).contains("upd.exe");
            });
    assertThat(drafts)
        .filteredOn(d -> d.subject().startsWith("service:"))
        .extracting(d -> d.technique().id())
        .containsExactly("T1543.003");
    assertThat(drafts)
        .filteredOn(d -> d.rule().equals("FILE_CHANGED"))
        .singleElement()
        .satisfies(
            d -> {
              assertThat(d.technique().id()).isEqualTo("T1565.001");
              assertThat(d.subject()).startsWith("/etc/hosts@bbbb");
            });
    assertThat(drafts)
        .filteredOn(d -> d.rule().equals("NEW_PRIVILEGED_ACCOUNT"))
        .extracting(Draft::subject, Draft::severity)
        .containsExactly(org.assertj.core.groups.Tuple.tuple("pirate", Severity.CRITICAL));
  }

  @Test
  @DisplayName(
      "Un compte qui devient administrateur est signalé ; une section absente ne fait rien oublier")
  void escalationAndPartialReport() {
    var baseline =
        EndpointRules.baselineOf(
            report(
                List.of(),
                List.of(),
                List.of(new Account("moussa", false, false)),
                List.of(),
                List.of()));
    var drafts =
        EndpointRules.evaluate(
            report(List.of(), null, List.of(new Account("moussa", true, false)), null, null),
            baseline);
    assertThat(rules(drafts)).containsExactly("NEW_PRIVILEGED_ACCOUNT");
    assertThat(drafts.getFirst().technique().id()).isEqualTo("T1098");
    assertThat(
            EndpointRules.baselineOf(
                new EndpointReport("Linux", true, null, null, null, null, null)))
        .isEmpty();
  }

  @Test
  @DisplayName(
      "Un service par utilisateur dont le suffixe change à chaque session n'est pas nouveau")
  void perUserServicesAcrossSessions() {
    var before =
        report(
            List.of(),
            List.of(
                new Persistence("service", "WpnUserService_14b498", null, null),
                new Persistence("service", "Spooler", null, null)),
            List.of(),
            List.of(),
            List.of());
    var after =
        report(
            List.of(),
            List.of(
                new Persistence("service", "WpnUserService_3f2a1c", null, null),
                new Persistence("service", "Spooler", null, null),
                new Persistence("service", "EvilSvc", null, null)),
            List.of(),
            List.of(),
            List.of());
    var drafts = EndpointRules.evaluate(after, EndpointRules.baselineOf(before));
    assertThat(drafts).extracting(Draft::subject).containsExactly("service:EvilSvc");
    assertThat(
            EndpointRules.persistenceKey(
                new Persistence("cron", "/etc/cron.d/job_abcd", null, null)))
        .as("seuls les services sont concernés")
        .isEqualTo("cron:/etc/cron.d/job_abcd");
  }

  @Test
  @DisplayName("Chemins et noms de programmes ramenés à une forme commune")
  void normalisation() {
    assertThat(EndpointRules.fromTemporaryDirectory("C:\\WINDOWS\\Temp\\a.exe")).isTrue();
    assertThat(EndpointRules.fromTemporaryDirectory("/usr/bin/tmpwatch")).isFalse();
    assertThat(EndpointRules.programName("NC.EXE", null)).isEqualTo("nc");
    assertThat(EndpointRules.programName("x", "/opt/bin/nmap")).isEqualTo("nmap");
    assertThat(EndpointRules.programName("x", "C:\\tools\\Rubeus.exe")).isEqualTo("rubeus");
  }
}

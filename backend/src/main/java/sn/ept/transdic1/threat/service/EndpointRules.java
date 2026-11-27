package sn.ept.transdic1.threat.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import sn.ept.transdic1.alerting.domain.Severity;
import sn.ept.transdic1.threat.domain.EndpointBaselineItem;
import sn.ept.transdic1.threat.domain.EndpointBaselineItem.Category;
import sn.ept.transdic1.threat.dto.EndpointReport;
import sn.ept.transdic1.threat.service.MitreAttack.Technique;

/**
 * Règles de l'EDR léger (ADR 0058), en calcul pur : à partir d'un rapport de poste et de sa ligne
 * de base, les constats à ouvrir. Deux familles :
 *
 * <ul>
 *   <li><b>Indicateurs</b>, jugés à chaque rapport, premier compris : programme lancé depuis un
 *       dossier temporaire, outil offensif connu, {@code /etc/ld.so.preload}, second compte
 *       superutilisateur, connexion sortante vers un port typique de porte dérobée.
 *   <li><b>Changements</b> par rapport à la ligne de base, à partir du deuxième rapport : nouvelle
 *       tâche planifiée, nouveau service ou démarrage automatique, nouveau compte, fichier sensible
 *       modifié. Le premier rapport ne signale aucun changement : il fixe la référence.
 * </ul>
 *
 * <p>Chaque constat dit pourquoi il existe, en clair, et à quelle technique MITRE ATT&CK il
 * renvoie. C'est un repère pour l'enquête, pas une preuve d'attaque : un administrateur qui
 * installe un service produit le même changement qu'un attaquant, d'où le jugement humain.
 */
public final class EndpointRules {
  private EndpointRules() {}

  /** Un constat à ouvrir. {@code subject} l'identifie de façon stable sur l'équipement. */
  public record Draft(
      String rule, Technique technique, Severity severity, String subject, String message) {}

  // ---------------------------------------------------------------------------- techniques
  static final Technique MALICIOUS_FILE =
      new Technique("T1204.002", "User Execution: Malicious File", "Execution");
  static final Technique RESOURCE_HIJACKING =
      new Technique("T1496", "Resource Hijacking", "Impact");
  static final Technique CREDENTIAL_DUMPING =
      new Technique("T1003", "OS Credential Dumping", "Credential Access");
  static final Technique PROTOCOL_TUNNELING =
      new Technique("T1572", "Protocol Tunneling", "Command and Control");
  static final Technique ACCOUNT_DISCOVERY =
      new Technique("T1087", "Account Discovery", "Discovery");
  static final Technique DYNAMIC_LINKER_HIJACKING =
      new Technique("T1574.006", "Hijack Execution Flow: Dynamic Linker Hijacking", "Persistence");
  static final Technique LOCAL_ACCOUNT =
      new Technique("T1136.001", "Create Account: Local Account", "Persistence");
  static final Technique ACCOUNT_MANIPULATION =
      new Technique("T1098", "Account Manipulation", "Persistence");
  static final Technique NON_STANDARD_PORT =
      new Technique("T1571", "Non-Standard Port", "Command and Control");
  static final Technique CRON =
      new Technique("T1053.003", "Scheduled Task/Job: Cron", "Persistence");
  static final Technique SYSTEMD_SERVICE =
      new Technique("T1543.002", "Create or Modify System Process: Systemd Service", "Persistence");
  static final Technique WINDOWS_SERVICE =
      new Technique("T1543.003", "Create or Modify System Process: Windows Service", "Persistence");
  static final Technique RUN_KEYS =
      new Technique(
          "T1547.001",
          "Boot or Logon Autostart Execution: Registry Run Keys / Startup Folder",
          "Persistence");
  static final Technique SHELL_CONFIGURATION =
      new Technique(
          "T1546.004",
          "Event Triggered Execution: Unix Shell Configuration Modification",
          "Persistence");
  static final Technique STORED_DATA_MANIPULATION =
      new Technique("T1565.001", "Data Manipulation: Stored Data Manipulation", "Impact");
  static final Technique MODIFY_AUTHENTICATION =
      new Technique("T1556", "Modify Authentication Process", "Credential Access");

  /** Outils offensifs connus, par nom de programme (sans « .exe »). */
  private record Tool(Technique technique, Severity severity, String family) {}

  private static final Map<String, Tool> TOOLS =
      Map.ofEntries(
          tool("xmrig", RESOURCE_HIJACKING, Severity.CRITICAL, "mineur de cryptomonnaie"),
          tool("minerd", RESOURCE_HIJACKING, Severity.CRITICAL, "mineur de cryptomonnaie"),
          tool("cpuminer", RESOURCE_HIJACKING, Severity.CRITICAL, "mineur de cryptomonnaie"),
          tool("xmr-stak", RESOURCE_HIJACKING, Severity.CRITICAL, "mineur de cryptomonnaie"),
          tool("nbminer", RESOURCE_HIJACKING, Severity.CRITICAL, "mineur de cryptomonnaie"),
          tool("ethminer", RESOURCE_HIJACKING, Severity.CRITICAL, "mineur de cryptomonnaie"),
          tool("mimikatz", CREDENTIAL_DUMPING, Severity.CRITICAL, "vol d'identifiants"),
          tool("lazagne", CREDENTIAL_DUMPING, Severity.CRITICAL, "vol d'identifiants"),
          tool("rubeus", CREDENTIAL_DUMPING, Severity.CRITICAL, "vol d'identifiants"),
          tool("pwdump", CREDENTIAL_DUMPING, Severity.CRITICAL, "vol d'identifiants"),
          tool("gsecdump", CREDENTIAL_DUMPING, Severity.CRITICAL, "vol d'identifiants"),
          tool("ngrok", PROTOCOL_TUNNELING, Severity.WARNING, "tunnel vers l'extérieur"),
          tool("chisel", PROTOCOL_TUNNELING, Severity.WARNING, "tunnel vers l'extérieur"),
          tool("frpc", PROTOCOL_TUNNELING, Severity.WARNING, "tunnel vers l'extérieur"),
          tool("socat", PROTOCOL_TUNNELING, Severity.WARNING, "relais réseau"),
          tool("ncat", PROTOCOL_TUNNELING, Severity.WARNING, "relais réseau"),
          tool("netcat", PROTOCOL_TUNNELING, Severity.WARNING, "relais réseau"),
          tool("nc", PROTOCOL_TUNNELING, Severity.WARNING, "relais réseau"),
          tool(
              "masscan",
              MitreAttack.NETWORK_SERVICE_DISCOVERY,
              Severity.WARNING,
              "balayage réseau"),
          tool("nmap", MitreAttack.NETWORK_SERVICE_DISCOVERY, Severity.WARNING, "balayage réseau"),
          tool("zmap", MitreAttack.NETWORK_SERVICE_DISCOVERY, Severity.WARNING, "balayage réseau"),
          tool("sharphound", ACCOUNT_DISCOVERY, Severity.WARNING, "reconnaissance d'annuaire"),
          tool("adfind", ACCOUNT_DISCOVERY, Severity.WARNING, "reconnaissance d'annuaire"));

  private static Map.Entry<String, Tool> tool(
      String name, Technique technique, Severity severity, String family) {
    return Map.entry(name, new Tool(technique, severity, family));
  }

  /** Dossiers d'où un programme légitime ne se lance presque jamais. */
  private static final List<String> TEMP_DIRECTORIES =
      List.of(
          "/tmp/",
          "/var/tmp/",
          "/dev/shm/",
          "/appdata/local/temp/",
          "/windows/temp/",
          "/users/public/",
          "/downloads/",
          "/$recycle.bin/");

  /** Ports de porte dérobée, d'IRC et de minage qu'aucun service de l'école n'utilise en sortie. */
  static final Set<Integer> SUSPICIOUS_PORTS =
      Set.of(1337, 3333, 4444, 5555, 6666, 6667, 9001, 12345, 14444, 31337, 45700);

  // ---------------------------------------------------------------------------- évaluation
  /**
   * @param baseline ligne de base actuelle, ou {@code null} au premier rapport (rien n'est alors
   *     signalé comme changement)
   */
  public static List<Draft> evaluate(EndpointReport report, List<EndpointBaselineItem> baseline) {
    List<Draft> drafts = new ArrayList<>();
    indicators(report, drafts);
    if (baseline != null) {
      changes(report, baseline, drafts);
    }
    return drafts;
  }

  private static void indicators(EndpointReport report, List<Draft> drafts) {
    if (report.processes() != null) {
      for (var process : report.processes()) {
        String path = process.path();
        if (path != null && fromTemporaryDirectory(path)) {
          drafts.add(
              new Draft(
                  "PROCESS_TEMP_PATH",
                  MALICIOUS_FILE,
                  Severity.WARNING,
                  path,
                  "Programme lancé depuis un dossier temporaire : " + path));
        }
        Tool tool = TOOLS.get(programName(process.name(), path));
        if (tool != null) {
          String where = path == null ? process.name() : path;
          drafts.add(
              new Draft(
                  "PROCESS_OFFENSIVE_TOOL",
                  tool.technique(),
                  tool.severity(),
                  where,
                  "Outil offensif en cours d'exécution (" + tool.family() + ") : " + where));
        }
      }
    }
    if (report.integrity() != null) {
      for (var file : report.integrity()) {
        if (file.path().equals("/etc/ld.so.preload") && file.present()) {
          drafts.add(
              new Draft(
                  "LD_PRELOAD",
                  DYNAMIC_LINKER_HIJACKING,
                  Severity.CRITICAL,
                  file.path(),
                  "/etc/ld.so.preload existe : chaque programme charge une bibliothèque imposée,"
                      + " technique typique d'un rootkit"));
        }
      }
    }
    if (report.accounts() != null) {
      for (var account : report.accounts()) {
        if (account.superuser() && !account.name().equals("root")) {
          drafts.add(
              new Draft(
                  "HIDDEN_SUPERUSER",
                  LOCAL_ACCOUNT,
                  Severity.CRITICAL,
                  account.name(),
                  "Second compte superutilisateur (UID 0) : " + account.name()));
        }
      }
    }
    if (report.connections() != null) {
      for (var connection : report.connections()) {
        if (SUSPICIOUS_PORTS.contains(connection.port())) {
          String target = connection.address() + ":" + connection.port();
          drafts.add(
              new Draft(
                  "SUSPICIOUS_PORT",
                  NON_STANDARD_PORT,
                  Severity.WARNING,
                  target,
                  "Connexion sortante vers un port de porte dérobée ou de minage : " + target));
        }
      }
    }
  }

  private static void changes(
      EndpointReport report, List<EndpointBaselineItem> baseline, List<Draft> drafts) {
    Map<String, EndpointBaselineItem> known = new java.util.HashMap<>();
    for (EndpointBaselineItem item : baseline) {
      known.put(item.category() + "|" + item.key(), item);
    }
    if (report.persistence() != null) {
      for (var entry : report.persistence()) {
        String key = persistenceKey(entry);
        EndpointBaselineItem before = known.get(Category.PERSISTENCE + "|" + key);
        Technique technique = persistenceTechnique(entry.kind());
        String label = persistenceLabel(entry.kind());
        String shown =
            entry.detail() == null ? entry.name() : entry.name() + " → " + entry.detail();
        if (before == null) {
          drafts.add(
              new Draft(
                  "NEW_PERSISTENCE",
                  technique,
                  Severity.WARNING,
                  key,
                  "Nouveau démarrage automatique (" + label + ") : " + shown));
        } else if (changed(before.fingerprint(), entry.fingerprint())) {
          drafts.add(
              new Draft(
                  "CHANGED_PERSISTENCE",
                  technique,
                  Severity.WARNING,
                  key + "@" + shortPrint(entry.fingerprint()),
                  "Démarrage automatique modifié (" + label + ") : " + shown));
        }
      }
    }
    if (report.accounts() != null) {
      for (var account : report.accounts()) {
        EndpointBaselineItem before = known.get(Category.ACCOUNT + "|" + account.name());
        boolean privileged = account.privileged() || account.superuser();
        if (before == null) {
          drafts.add(
              new Draft(
                  privileged ? "NEW_PRIVILEGED_ACCOUNT" : "NEW_ACCOUNT",
                  privileged ? ACCOUNT_MANIPULATION : LOCAL_ACCOUNT,
                  privileged ? Severity.CRITICAL : Severity.WARNING,
                  account.name(),
                  (privileged ? "Nouveau compte privilégié : " : "Nouveau compte : ")
                      + account.name()));
        } else if (privileged && !"privileged".equals(before.fingerprint())) {
          drafts.add(
              new Draft(
                  "NEW_PRIVILEGED_ACCOUNT",
                  ACCOUNT_MANIPULATION,
                  Severity.CRITICAL,
                  account.name(),
                  "Compte devenu privilégié (administrateur, sudo) : " + account.name()));
        }
      }
    }
    if (report.integrity() != null) {
      for (var file : report.integrity()) {
        EndpointBaselineItem before = known.get(Category.INTEGRITY + "|" + file.path());
        String now = integrityPrint(file);
        if (before != null && now != null && changed(before.fingerprint(), now)) {
          drafts.add(
              new Draft(
                  "FILE_CHANGED",
                  fileTechnique(file.path()),
                  Severity.WARNING,
                  file.path() + "@" + shortPrint(now),
                  "Fichier sensible modifié : " + file.path()));
        }
      }
    }
  }

  // ---------------------------------------------------------------------------- ligne de base
  /** Ligne de base tirée d'un rapport : les sections absentes ne font rien oublier. */
  public static List<EndpointBaselineItem> baselineOf(EndpointReport report) {
    List<EndpointBaselineItem> items = new ArrayList<>();
    if (report.persistence() != null) {
      for (var entry : report.persistence()) {
        items.add(
            new EndpointBaselineItem(
                Category.PERSISTENCE, persistenceKey(entry), entry.fingerprint()));
      }
    }
    if (report.accounts() != null) {
      for (var account : report.accounts()) {
        items.add(
            new EndpointBaselineItem(
                Category.ACCOUNT,
                account.name(),
                account.privileged() || account.superuser() ? "privileged" : "user"));
      }
    }
    if (report.integrity() != null) {
      for (var file : report.integrity()) {
        String print = integrityPrint(file);
        if (print != null) {
          items.add(new EndpointBaselineItem(Category.INTEGRITY, file.path(), print));
        }
      }
    }
    return items;
  }

  // ---------------------------------------------------------------------------- outils
  /**
   * Services Windows « par utilisateur » (WpnUserService_14b498, AarSvc_14b498) : Windows leur
   * ajoute un suffixe hexadécimal qui change à chaque ouverture de session. Sans le retirer, chaque
   * redémarrage ferait passer tous ces services pour nouveaux.
   */
  private static final Pattern PER_USER_SERVICE = Pattern.compile("(.+)_[0-9a-fA-F]{4,8}");

  static String persistenceKey(EndpointReport.Persistence entry) {
    String name = entry.name();
    if ("service".equals(entry.kind())) {
      var matcher = PER_USER_SERVICE.matcher(name);
      if (matcher.matches()) {
        name = matcher.group(1) + "_*";
      }
    }
    return entry.kind() + ":" + name;
  }

  static boolean fromTemporaryDirectory(String path) {
    // Chemins Windows et Linux ramenés à la même forme : minuscules, barres obliques.
    String normal = path.toLowerCase(Locale.ROOT).replace('\\', '/');
    return TEMP_DIRECTORIES.stream().anyMatch(normal::contains);
  }

  static String programName(String name, String path) {
    String base =
        path == null
            ? name
            : path.substring(Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1);
    String lower = base.toLowerCase(Locale.ROOT);
    return lower.endsWith(".exe") ? lower.substring(0, lower.length() - 4) : lower;
  }

  /** Empreinte d'un fichier sensible : son hachage, « absent », ou null s'il est illisible. */
  private static String integrityPrint(EndpointReport.Integrity file) {
    if (!file.present()) {
      return "absent";
    }
    return file.sha256();
  }

  private static boolean changed(String before, String now) {
    return before != null && now != null && !before.equals(now);
  }

  private static String shortPrint(String print) {
    if (print == null) {
      return "?";
    }
    return print.length() <= 12 ? print : print.substring(0, 12);
  }

  private static Technique persistenceTechnique(String kind) {
    return switch (kind) {
      case "systemd" -> SYSTEMD_SERVICE;
      case "service" -> WINDOWS_SERVICE;
      case "run-key", "startup" -> RUN_KEYS;
      case "shell-init" -> SHELL_CONFIGURATION;
      default -> CRON;
    };
  }

  private static String persistenceLabel(String kind) {
    return switch (kind) {
      case "systemd" -> "unité systemd";
      case "service" -> "service Windows";
      case "run-key" -> "clé Run du registre";
      case "startup" -> "dossier de démarrage";
      case "shell-init" -> "script d'ouverture de session";
      case "rc" -> "rc.local";
      default -> "tâche cron";
    };
  }

  private static Technique fileTechnique(String path) {
    String lower = path.toLowerCase(Locale.ROOT);
    if (lower.endsWith("/passwd") || lower.endsWith("/group")) {
      return ACCOUNT_MANIPULATION;
    }
    if (lower.endsWith("sshd_config")) {
      return MODIFY_AUTHENTICATION;
    }
    if (lower.endsWith("crontab")) {
      return CRON;
    }
    if (lower.endsWith("ld.so.preload")) {
      return DYNAMIC_LINKER_HIJACKING;
    }
    return STORED_DATA_MANIPULATION; // hosts, resolv.conf : détournement des noms
  }
}

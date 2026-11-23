package sn.ept.transdic1.threat.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import sn.ept.transdic1.threat.domain.SecurityEventKind;
import sn.ept.transdic1.threat.service.WebAttackSignatures.Family;

/**
 * Repère MITRE ATT&CK des scénarios de sécurité (ADR 0048) : à quelle technique de la matrice
 * correspond ce que la supervision a vu.
 *
 * <p>Ce n'est qu'un <b>repère</b>, pas une classification : il aide à parler le même langage que
 * les équipes de sécurité et à retrouver la documentation d'une technique, il ne dit pas qui
 * attaque ni avec quel outil. Un scénario sans technique nette n'en reçoit pas — le soupçon d'un
 * modèle appris, ou un volume inhabituel, ne se rattache honnêtement à aucune ligne de la matrice,
 * et en inventer une ferait passer une hypothèse pour un fait.
 *
 * <p>Les identifiants et noms sont ceux d'ATT&CK Enterprise v20.1, vérifiés sur le site du MITRE.
 */
public final class MitreAttack {

  /** Une technique de la matrice : identifiant, nom officiel, tactique principale. */
  public record Technique(String id, String name, String tactic) {
    /** Adresse de la fiche officielle ; une sous-technique est sous son parent. */
    public String url() {
      return "https://attack.mitre.org/techniques/" + id.replace('.', '/') + "/";
    }
  }

  public static final Technique EXPLOIT_PUBLIC_APP =
      new Technique("T1190", "Exploit Public-Facing Application", "Initial Access");
  public static final Technique COMMAND_INTERPRETER =
      new Technique("T1059", "Command and Scripting Interpreter", "Execution");
  public static final Technique BRUTE_FORCE =
      new Technique("T1110", "Brute Force", "Credential Access");
  public static final Technique VALID_ACCOUNTS =
      new Technique("T1078", "Valid Accounts", "Initial Access");
  public static final Technique WORDLIST_SCANNING =
      new Technique("T1595.003", "Active Scanning: Wordlist Scanning", "Reconnaissance");
  public static final Technique NETWORK_SERVICE_DISCOVERY =
      new Technique("T1046", "Network Service Discovery", "Discovery");
  public static final Technique NETWORK_DOS =
      new Technique("T1498", "Network Denial of Service", "Impact");

  private static final Map<Family, List<Technique>> BY_FAMILY = new LinkedHashMap<>();

  static {
    BY_FAMILY.put(Family.INJECTION_SQL, List.of(EXPLOIT_PUBLIC_APP));
    BY_FAMILY.put(Family.XSS, List.of(EXPLOIT_PUBLIC_APP));
    BY_FAMILY.put(Family.TRAVERSEE_REPERTOIRE, List.of(EXPLOIT_PUBLIC_APP));
    // L'exécution de commande est à la fois la porte d'entrée et ce qu'elle ouvre : les deux
    // techniques se lisent, la seconde étant le but de la requête.
    BY_FAMILY.put(Family.EXECUTION_COMMANDE, List.of(EXPLOIT_PUBLIC_APP, COMMAND_INTERPRETER));
    BY_FAMILY.put(Family.INJECTION_MODELE, List.of(EXPLOIT_PUBLIC_APP));
    BY_FAMILY.put(Family.RECONNAISSANCE, List.of(WORDLIST_SCANNING));
  }

  private MitreAttack() {}

  /** Techniques d'une famille d'attaque reconnue par signature. */
  public static List<Technique> forFamily(Family family) {
    return BY_FAMILY.getOrDefault(family, List.of());
  }

  /**
   * Techniques d'une ligne de journal retenue.
   *
   * @param families libellés de familles tels que {@code SecurityEvent} les stocke, séparés par « ,
   *     » ; absent pour les natures qui n'en portent pas
   */
  public static List<Technique> forEvent(SecurityEventKind kind, String families) {
    return switch (kind) {
      case AUTH_FAILURE -> List.of(BRUTE_FORCE);
      case WEB_ATTACK -> fromLabels(families);
      // Une connexion réussie ne prouve rien d'hostile par elle-même : elle n'a de sens qu'après
      // des échecs ou d'une adresse inconnue, ce que seule l'alerte correspondante sait dire.
      case AUTH_SUCCESS -> List.of();
      // Un modèle appris soupçonne une forme, sans nommer de technique ; et une erreur isolée
      // n'est pas un balayage, c'est leur rafale qui l'est (voir forAlert).
      case WEB_SUSPECT, WEB_ERROR -> List.of();
    };
  }

  /** Techniques d'une nature d'alerte de sécurité. */
  public static List<Technique> forAlert(SecurityAlerts.Subject subject) {
    return switch (subject) {
      case ATTACK -> List.of(EXPLOIT_PUBLIC_APP);
      case AUTH -> List.of(BRUTE_FORCE);
      case SCAN -> List.of(WORDLIST_SCANNING);
      case LOGIN -> List.of(VALID_ACCOUNTS);
      case NETWORK_SCAN -> List.of(NETWORK_SERVICE_DISCOVERY);
      case FLOOD -> List.of(NETWORK_DOS);
      // Soupçon d'un modèle, ou écart de volume : aucune technique ne s'y rattache honnêtement.
      case SUSPECT, VOLUME -> List.of();
    };
  }

  private static List<Technique> fromLabels(String families) {
    if (families == null || families.isBlank()) {
      return List.of();
    }
    var found = new ArrayList<Technique>();
    for (String label : families.split(",")) {
      String wanted = label.trim();
      for (Family family : Family.values()) {
        if (family.label().equals(wanted)) {
          for (Technique technique : forFamily(family)) {
            if (!found.contains(technique)) {
              found.add(technique);
            }
          }
        }
      }
    }
    return List.copyOf(found);
  }

  /** « T1190, T1059 » : la forme courte que l'écran affiche à côté d'une ligne. */
  public static String ids(List<Technique> techniques) {
    return techniques.stream().map(Technique::id).collect(Collectors.joining(", "));
  }
}

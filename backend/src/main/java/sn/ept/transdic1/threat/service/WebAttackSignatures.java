package sn.ept.transdic1.threat.service;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Reconnaissance d'attaques dans une requête web, par signatures, sur le principe du score cumulé
 * de l'OWASP Core Rule Set : chaque signature reconnue ajoute des points selon sa gravité, et la
 * requête est tenue pour une attaque au-delà d'un seuil. Plusieurs petits indices valent ainsi une
 * grosse signature, et une seule signature faible ne déclenche rien.
 *
 * <p>Avant comparaison, la requête est décodée (deux fois au plus : les outils d'attaque encodent
 * souvent en double pour passer les filtres) et mise en minuscules.
 *
 * <p>Ce que cela ne fait pas : ce n'est pas un pare-feu applicatif. On lit un journal, donc on voit
 * l'attaque après coup et jamais le corps de la requête. Les formes inconnues passent ; c'est le
 * rôle d'un modèle appris (S4 du plan) de les repérer plus tard.
 */
public final class WebAttackSignatures {
  /** Score à partir duquel la requête est tenue pour une attaque. */
  public static final int ATTACK_SCORE = 5;

  /**
   * Décodages successifs tentés : au-delà, un texte normal finirait par ressembler à autre chose.
   */
  private static final int MAX_DECODES = 2;

  /** Longueur examinée : une requête démesurée est tronquée avant analyse. */
  static final int MAX_LENGTH = 4000;

  /** Famille d'attaque reconnue, avec ce qu'elle coûte. */
  public enum Family {
    INJECTION_SQL("Injection SQL", 5),
    XSS("Script injecté (XSS)", 5),
    TRAVERSEE_REPERTOIRE("Traversée de répertoire", 5),
    EXECUTION_COMMANDE("Exécution de commande", 5),
    INJECTION_MODELE("Injection de modèle (JNDI, expression)", 5),
    RECONNAISSANCE("Reconnaissance (sondage de chemins connus)", 2);

    private final String label;
    private final int score;

    Family(String label, int score) {
      this.label = label;
      this.score = score;
    }

    public String label() {
      return label;
    }

    public int score() {
      return score;
    }
  }

  private record Signature(Family family, Pattern pattern) {}

  private static final List<Signature> SIGNATURES =
      List.of(
          // Injection SQL : mots-clés enchaînés, commentaires de fin, fonctions de temporisation.
          sig(Family.INJECTION_SQL, "union\\s+(all\\s+)?select"),
          sig(Family.INJECTION_SQL, "\\bor\\b\\s+['\"]?\\d+['\"]?\\s*=\\s*['\"]?\\d+"),
          sig(Family.INJECTION_SQL, "\\b(sleep|benchmark|pg_sleep|waitfor\\s+delay)\\s*\\("),
          sig(Family.INJECTION_SQL, "information_schema|sysobjects|pg_catalog"),
          sig(Family.INJECTION_SQL, ";\\s*(drop|delete|update|insert)\\s+"),
          // Script injecté : balises et gestionnaires d'événements.
          sig(Family.XSS, "<\\s*script"),
          sig(Family.XSS, "javascript\\s*:"),
          sig(Family.XSS, "\\bon(error|load|click|mouseover)\\s*="),
          sig(Family.XSS, "<\\s*(svg|iframe|img)[^>]*\\bon\\w+\\s*="),
          sig(Family.XSS, "document\\.(cookie|location)"),
          // Traversée de répertoire et lecture de fichiers du système.
          sig(Family.TRAVERSEE_REPERTOIRE, "\\.\\./|\\.\\.\\\\"),
          sig(Family.TRAVERSEE_REPERTOIRE, "/etc/(passwd|shadow)|boot\\.ini|win\\.ini"),
          sig(Family.TRAVERSEE_REPERTOIRE, "\\bfile\\s*:/{2}"),
          // Exécution de commande.
          sig(
              Family.EXECUTION_COMMANDE,
              "[;|&]\\s*(cat|ls|id|whoami|uname|wget|curl|nc|bash|sh)\\b"),
          sig(Family.EXECUTION_COMMANDE, "\\$\\(|`[^`]+`"),
          // Injection de modèle et recherche d'objet distant (Log4Shell et semblables).
          sig(Family.INJECTION_MODELE, "\\$\\{\\s*(jndi|env|sys)\\s*:"),
          sig(Family.INJECTION_MODELE, "\\{\\{\\s*\\w+\\s*\\}\\}"),
          // Reconnaissance : chemins que personne de légitime ne demande sur le parc de l'école.
          sig(Family.RECONNAISSANCE, "/(wp-admin|wp-login|xmlrpc\\.php)"),
          sig(Family.RECONNAISSANCE, "/(phpmyadmin|pma|adminer)\\b"),
          sig(Family.RECONNAISSANCE, "/\\.(env|git|svn|aws)(/|\\?|$)"),
          sig(Family.RECONNAISSANCE, "/vendor/phpunit|/cgi-bin/|/shell\\?"),
          sig(Family.RECONNAISSANCE, "\\.(bak|old|sql|zip|tar\\.gz)(\\?|$)"));

  private static Signature sig(Family family, String regex) {
    return new Signature(family, Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
  }

  private WebAttackSignatures() {}

  /**
   * Verdict sur une requête.
   *
   * @param score points cumulés de toutes les signatures reconnues
   * @param families familles reconnues, sans répétition, dans l'ordre où elles apparaissent
   */
  public record Verdict(int score, List<Family> families) {
    public boolean isAttack() {
      return score >= ATTACK_SCORE;
    }

    /** Résumé en français, pour le message d'alerte et l'écran. */
    public String summary() {
      return families.stream()
          .map(Family::label)
          .distinct()
          .reduce((a, b) -> a + ", " + b)
          .orElse("");
    }
  }

  /** Examine le chemin et la chaîne de requête d'une demande web. */
  public static Verdict examine(String request) {
    String normalized = normalize(request);
    if (normalized.isEmpty()) {
      return new Verdict(0, List.of());
    }
    var families = new ArrayList<Family>();
    int score = 0;
    for (Signature signature : SIGNATURES) {
      if (!signature.pattern().matcher(normalized).find()) {
        continue;
      }
      // Chaque signature compte, comme dans l'OWASP CRS : trois indices faibles sur la même
      // requête (un chemin d'outil, un fichier caché, une sauvegarde) valent une vraie alerte.
      score += signature.family().score();
      if (!families.contains(signature.family())) {
        families.add(signature.family());
      }
    }
    return new Verdict(score, List.copyOf(families));
  }

  /**
   * Décode (deux fois au plus) et met en minuscules. Un décodage qui échoue laisse la valeur telle
   * quelle : une requête mal formée ne doit pas faire échouer l'analyse.
   */
  static String normalize(String request) {
    if (request == null) {
      return "";
    }
    String value = request.length() > MAX_LENGTH ? request.substring(0, MAX_LENGTH) : request;
    for (int i = 0; i < MAX_DECODES; i++) {
      String decoded = decodeOnce(value);
      if (decoded.equals(value)) {
        break;
      }
      value = decoded;
    }
    return value.toLowerCase(Locale.ROOT);
  }

  private static String decodeOnce(String value) {
    try {
      return URLDecoder.decode(value, StandardCharsets.UTF_8);
    } catch (RuntimeException e) {
      return value;
    }
  }
}

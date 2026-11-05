package sn.ept.transdic1.events.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import sn.ept.transdic1.events.domain.EventRule;

/**
 * Décide ce qu'un événement fait à une règle : l'ouvrir (tous les critères d'ouverture renseignés
 * sont satisfaits), la résoudre (condition de retour satisfaite, sans regarder la sévérité
 * minimale) ou rien. L'ouverture l'emporte si les deux conditions sont vraies. Les motifs compilés
 * sont gardés en cache (taille bornée).
 */
@Component
public class EventRuleMatcher {
  private static final int MAX_CACHED = 500;

  public enum Decision {
    OPEN,
    RESOLVE,
    NONE
  }

  /** Décision et indicateur « une expression a été interrompue » (budget dépassé). */
  public record Verdict(Decision decision, boolean aborted) {}

  private final Map<String, Pattern> patterns = new ConcurrentHashMap<>();

  public Verdict evaluate(EventRule rule, EventFacts facts) {
    if (!inScope(rule, facts) || (rule.getSource() != null && rule.getSource() != facts.source())) {
      return new Verdict(Decision.NONE, false);
    }
    boolean[] aborted = {false};
    boolean opens =
        (rule.getMinSeverity() == null || facts.severity().atLeast(rule.getMinSeverity()))
            && oidMatches(rule.getOidPrefix(), facts.trapOid())
            && regexMatches(rule.getMessageRegex(), facts.text(), aborted);
    if (opens) {
      return new Verdict(Decision.OPEN, aborted[0]);
    }
    boolean resolves =
        rule.hasResolveCondition()
            && oidMatches(rule.getResolveOid(), facts.trapOid())
            && regexMatches(rule.getResolveRegex(), facts.text(), aborted);
    return new Verdict(resolves ? Decision.RESOLVE : Decision.NONE, aborted[0]);
  }

  private static boolean inScope(EventRule rule, EventFacts facts) {
    if (rule.getSiteIds().isEmpty() && rule.getEquipmentIds().isEmpty()) {
      return true;
    }
    return rule.getEquipmentIds().contains(facts.equipmentId())
        || (facts.siteId() != null && rule.getSiteIds().contains(facts.siteId()));
  }

  /** OID égal au préfixe ou situé sous lui (frontière de nœud : 1.3.6.1.5.3 n'inclut pas .30). */
  static boolean oidMatches(String prefix, String oid) {
    if (prefix == null) {
      return true;
    }
    if (oid == null) {
      return false;
    }
    String p = normalizeOid(prefix);
    String o = normalizeOid(oid);
    return o.equals(p) || o.startsWith(p + ".");
  }

  public static String normalizeOid(String oid) {
    String trimmed = oid.trim();
    return trimmed.startsWith(".") ? trimmed.substring(1) : trimmed;
  }

  private boolean regexMatches(String regex, String text, boolean[] aborted) {
    if (regex == null) {
      return true;
    }
    Pattern pattern = patterns.get(regex);
    if (pattern == null) {
      try {
        pattern = SafeRegex.compile(regex);
      } catch (IllegalArgumentException e) {
        return false;
      }
      if (patterns.size() >= MAX_CACHED) {
        patterns.clear();
      }
      patterns.put(regex, pattern);
    }
    var outcome = SafeRegex.find(pattern, text == null ? "" : text);
    if (outcome == SafeRegex.Outcome.ABORTED) {
      aborted[0] = true;
    }
    return outcome == SafeRegex.Outcome.MATCH;
  }
}

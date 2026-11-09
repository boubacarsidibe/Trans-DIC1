package sn.ept.transdic1.diagnostic.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import sn.ept.transdic1.diagnostic.domain.CauseSource;

/**
 * Score déterministe des causes probables (ADR 0024). Tous les poids sont ici, et seulement ici.
 *
 * <pre>
 * score = arrondi₃( poids(source) × force × décroissance(source, |preuve − ouverture|) )
 * décroissance = plancher + (1 − plancher) × 2^(−distance / demi-vie)
 * </pre>
 *
 * <ul>
 *   <li>{@code force} (0..1) est fixée par la règle qui a trouvé la preuve (un trap linkDown vaut
 *       plus qu'un message syslog d'erreur, une alerte de collecte en amont plus qu'une alerte de
 *       seuil en amont...). Hors bornes, elle est ramenée dans [0, 1].
 *   <li>La demi-vie est de 15 minutes : une preuve vieille de 15 minutes vaut moitié moins qu'une
 *       preuve contemporaine de l'ouverture.
 *   <li>Le plancher garde de la valeur aux états durables : un amont en panne depuis une heure
 *       reste une cause forte ; une tendance de saturation (en jours) ne décroît pas du tout.
 * </ul>
 *
 * Classement : score décroissant, puis ordre de {@link CauseSource} (le plus structurel d'abord),
 * puis preuve la plus proche de l'ouverture, puis libellé : le résultat ne dépend jamais de l'ordre
 * des données lues. Cinq causes au plus ; une cause de score nul n'est pas proposée.
 */
public final class CauseScoring {

  /** Poids d'une source et plancher de sa décroissance. */
  public record Weight(double weight, double floor) {}

  public static final Map<CauseSource, Weight> WEIGHTS;

  static {
    var weights = new EnumMap<CauseSource, Weight>(CauseSource.class);
    weights.put(CauseSource.AMONT_EN_ALERTE, new Weight(1.0, 0.5));
    weights.put(CauseSource.EVENEMENT_RECENT, new Weight(0.9, 0.0));
    weights.put(CauseSource.CHANGEMENT_RECENT, new Weight(0.8, 0.0));
    weights.put(CauseSource.SILENCE_COLLECTE, new Weight(0.7, 0.5));
    weights.put(CauseSource.TENDANCE_SATURATION, new Weight(0.6, 1.0));
    weights.put(CauseSource.ANOMALIE, new Weight(0.5, 0.3));
    WEIGHTS = java.util.Collections.unmodifiableMap(weights);
  }

  /** Demi-vie de la décroissance temporelle, en minutes. */
  public static final double HALF_LIFE_MINUTES = 15.0;

  /** Nombre maximal de causes proposées. */
  public static final int MAX_CAUSES = 5;

  /**
   * Forces des preuves (0..1), par règle de détection. Comme les poids, elles ne sont écrites
   * qu'ici.
   */
  public static final class Strength {
    /** Alerte de collecte sur un équipement en amont : il est injoignable. */
    public static final double UPSTREAM_COLLECTION = 1.0;

    /** Autre alerte (seuil, événement...) sur un équipement en amont. */
    public static final double UPSTREAM_OTHER = 0.6;

    /** Facteur appliqué à un événement ou un changement qui vise un équipement en amont. */
    public static final double UPSTREAM_FACTOR = 0.8;

    /** Trap linkDown (interface coupée). */
    public static final double LINK_DOWN = 1.0;

    /** Trap coldStart ou warmStart (redémarrage). */
    public static final double RESTART = 0.9;

    /** Message de sévérité EMERGENCY, ALERT ou CRITICAL. */
    public static final double CRITICAL_MESSAGE = 0.8;

    /** Message de sévérité ERROR. */
    public static final double ERROR_MESSAGE = 0.6;

    /** Trap authenticationFailure. */
    public static final double AUTH_FAILURE = 0.5;

    /** L'alerte est elle-même une prévision : la tendance est la cause. */
    public static final double FORECAST_SELF = 1.0;

    /** Prévision active sur le même indicateur que l'alerte. */
    public static final double FORECAST_SAME_METRIC = 0.7;

    /** Prévision active sur un autre indicateur de l'équipement. */
    public static final double FORECAST_OTHER_METRIC = 0.4;

    /** Anomalie active sur le même indicateur que l'alerte. */
    public static final double ANOMALY_SAME_METRIC = 1.0;

    /** Anomalie active sur un autre indicateur de l'équipement. */
    public static final double ANOMALY_OTHER_METRIC = 0.6;

    /** L'alerte est elle-même une perte de collecte : le silence décrit, il n'explique pas. */
    public static final double SILENCE_SELF = 0.5;

    /** Perte de collecte en cours sur l'équipement d'une autre alerte : les mesures manquent. */
    public static final double SILENCE_OTHER = 1.0;

    private Strength() {}
  }

  /** Force d'un changement tracé par l'audit, par action (liste blanche). */
  public static final Map<String, Double> CHANGE_STRENGTH =
      Map.ofEntries(
          Map.entry("RULE_CREATED", 0.8),
          Map.entry("RULE_UPDATED", 0.9),
          Map.entry("EVENT_RULE_CREATED", 0.8),
          Map.entry("EVENT_RULE_UPDATED", 0.9),
          Map.entry("EQUIPMENT_UPDATED", 0.6),
          Map.entry("TEMPLATE_APPLIED", 0.7),
          Map.entry("COLLECTION_CONFIGURED", 0.8),
          Map.entry("COLLECTION_DISABLED", 0.9),
          Map.entry("AGENT_REGISTERED", 0.6),
          Map.entry("AGENT_REVOKED", 0.9),
          Map.entry("TOPOLOGY_LINK_ADDED", 0.5),
          Map.entry("TOPOLOGY_LINK_REMOVED", 0.5),
          Map.entry("PROBE_CREATED", 0.6),
          Map.entry("PROBE_UPDATED", 0.7),
          Map.entry("PROBE_DISABLED", 0.6),
          Map.entry("MAINTENANCE_PLANNED", 0.6),
          Map.entry("MAINTENANCE_CANCELLED", 0.7),
          Map.entry("SITE_UPDATED", 0.3));

  private CauseScoring() {}

  /** Décroissance (0..1) d'une preuve selon sa distance à l'ouverture de l'alerte. */
  public static double decay(CauseSource source, Instant at, Instant reference) {
    if (at == null || reference == null) {
      return 1.0;
    }
    double minutes = Math.abs(Duration.between(reference, at).toMillis()) / 60_000.0;
    double floor = WEIGHTS.get(source).floor();
    return floor + (1.0 - floor) * Math.pow(2.0, -minutes / HALF_LIFE_MINUTES);
  }

  /** Score (0..1, arrondi au millième) d'une preuve. */
  public static double score(CauseSource source, double strength, Instant at, Instant reference) {
    double bounded = Double.isNaN(strength) ? 0.0 : Math.max(0.0, Math.min(1.0, strength));
    double raw = WEIGHTS.get(source).weight() * bounded * decay(source, at, reference);
    return Math.round(Math.max(0.0, Math.min(1.0, raw)) * 1000.0) / 1000.0;
  }

  /** Classe les candidates (ordre total et stable) et garde les {@value #MAX_CAUSES} premières. */
  public static List<Scored> rank(List<CauseCandidate> candidates, Instant reference) {
    Comparator<Scored> order =
        Comparator.comparingDouble(Scored::score)
            .reversed()
            .thenComparing(s -> s.candidate().source())
            .thenComparingLong(s -> distance(s.candidate().at(), reference))
            .thenComparing(s -> s.candidate().libelle() == null ? "" : s.candidate().libelle());
    return candidates.stream()
        .map(c -> new Scored(c, score(c.source(), c.strength(), c.at(), reference)))
        .filter(s -> s.score() > 0.0)
        .sorted(order)
        .limit(MAX_CAUSES)
        .toList();
  }

  private static long distance(Instant at, Instant reference) {
    return at == null || reference == null
        ? 0L
        : Math.abs(Duration.between(reference, at).toMillis());
  }
}

package sn.ept.transdic1.alerting.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import sn.ept.transdic1.alerting.domain.ComparisonOperator;

/**
 * Seuils proposés d'après l'historique d'un indicateur (M14 du plan, ADR 0035).
 *
 * <p>Calcul pur : centiles de ce qui a été mesuré, puis arrondi à un nombre qu'un humain aurait
 * écrit. Régler une règle revient aujourd'hui à essayer 80, trouver que cela sonne trop, essayer
 * 90, et recommencer. Les centiles disent d'emblée ce qui est habituel pour <i>cette</i> machine.
 *
 * <p>Pourquoi des centiles et non la moyenne : un indicateur de supervision n'est presque jamais
 * symétrique. L'occupation d'un disque monte par paliers, la charge d'un processeur a de longues
 * pointes brèves. Le 95ᵉ centile répond exactement à la question qu'on se pose — « au-dessus de
 * quoi suis-je dans les 5 % du temps les plus chargés ? » — là où une moyenne et un écart-type
 * supposent une cloche qui n'existe pas.
 */
public final class ThresholdAdvisor {
  /** Sous ce nombre de mesures, aucun centile n'est digne de foi. */
  public static final int MIN_SAMPLES = 100;

  private ThresholdAdvisor() {}

  /** Ce que l'historique contient, avant toute proposition. */
  public record Distribution(
      int samples,
      BigDecimal minimum,
      BigDecimal p1,
      BigDecimal p5,
      BigDecimal p10,
      BigDecimal median,
      BigDecimal p90,
      BigDecimal p95,
      BigDecimal p99,
      BigDecimal maximum) {}

  /** Un seuil proposé, avec ce qu'il vaut. */
  public record Proposal(
      String label, String rationale, int percentile, BigDecimal threshold, BigDecimal recovery) {}

  /**
   * Centiles de la série. Les valeurs sont triées ici : l'appelant n'a pas à s'en soucier.
   *
   * @return null si l'historique est trop maigre pour en tirer quoi que ce soit
   */
  public static Distribution describe(List<BigDecimal> values) {
    if (values == null || values.size() < MIN_SAMPLES) {
      return null;
    }
    List<BigDecimal> sorted = values.stream().sorted().toList();
    return new Distribution(
        sorted.size(),
        sorted.get(0),
        percentile(sorted, 1),
        percentile(sorted, 5),
        percentile(sorted, 10),
        percentile(sorted, 50),
        percentile(sorted, 90),
        percentile(sorted, 95),
        percentile(sorted, 99),
        sorted.get(sorted.size() - 1));
  }

  /** Centile d'une série déjà triée, par la méthode du rang le plus proche. */
  static BigDecimal percentile(List<BigDecimal> sorted, int percentile) {
    if (sorted.isEmpty()) {
      return BigDecimal.ZERO;
    }
    int rank = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
    return sorted.get(Math.min(Math.max(rank, 0), sorted.size() - 1));
  }

  /**
   * Trois propositions, de la plus prudente à la plus stricte, pour l'opérateur donné.
   *
   * <p>Au-dessus d'un seuil, on prend les centiles hauts ; en dessous, les centiles bas — un seuil
   * « moins de 10 % de mémoire libre » se règle sur le bas de la distribution, pas sur le haut.
   * L'opérateur « égal » n'a pas de seuil à proposer : une égalité n'a pas de marge.
   *
   * <p>Le seuil de retour est placé un cran plus bas (le centile précédent), pour que l'alerte ne
   * clignote pas au retour à la normale, et jamais du mauvais côté du seuil d'ouverture.
   */
  public static List<Proposal> propose(
      ComparisonOperator operator, Distribution distribution, String unit) {
    if (distribution == null || operator == ComparisonOperator.EQUAL) {
      return List.of();
    }
    boolean above =
        operator == ComparisonOperator.GREATER_THAN
            || operator == ComparisonOperator.GREATER_OR_EQUAL;
    var proposals = new java.util.ArrayList<Proposal>(3);
    proposals.add(
        proposal(
            "Prudent",
            "alerte seulement sur ce qui sort vraiment de l'habituel de cette machine",
            above ? 99 : 1,
            above ? distribution.p99() : distribution.p1(),
            above ? distribution.p95() : distribution.p5(),
            unit));
    proposals.add(
        proposal(
            "Équilibré",
            "alerte sur les 5 % de mesures les plus éloignées de l'ordinaire",
            above ? 95 : 5,
            above ? distribution.p95() : distribution.p5(),
            above ? distribution.p90() : distribution.p10(),
            unit));
    proposals.add(
        proposal(
            "Sensible",
            "alerte dès que la machine sort de son régime courant ; attendez-vous à du bruit",
            above ? 90 : 10,
            above ? distribution.p90() : distribution.p10(),
            distribution.median(),
            unit));
    // Deux centiles peuvent tomber au même endroit après arrondi, sur une série peu dispersée.
    // Montrer deux fois le même seuil sous deux noms ne rendrait service à personne : on ne
    // garde que le premier, c'est-à-dire le plus prudent.
    var distinct = new java.util.ArrayList<Proposal>(proposals.size());
    for (Proposal proposal : proposals) {
      boolean seen =
          distinct.stream().anyMatch(kept -> kept.threshold().compareTo(proposal.threshold()) == 0);
      if (!seen) {
        distinct.add(proposal);
      }
    }
    return List.copyOf(distinct);
  }

  private static Proposal proposal(
      String label,
      String rationale,
      int percentile,
      BigDecimal threshold,
      BigDecimal recovery,
      String unit) {
    BigDecimal rounded = round(threshold, unit);
    BigDecimal roundedRecovery = round(recovery, unit);
    if (rounded.compareTo(roundedRecovery) == 0) {
      roundedRecovery = null; // pas de seuil de retour s'il se confond avec l'ouverture
    }
    return new Proposal(label, rationale, percentile, rounded, roundedRecovery);
  }

  /**
   * Arrondi à un nombre qu'un humain aurait écrit : 87,3 devient 85, 65,62 devient 65, et 1 234 567
   * octets deviennent 1 200 000. Un seuil n'a pas à être précis au centième, et un chiffre rond se
   * retient, se discute et se corrige.
   *
   * <p>L'arrondi ne dépend pas de l'unité, qui est facultative et souvent laissée vide au moment où
   * l'on cherche justement le seuil. Le pourcentage ajoute seulement une borne : un seuil au-dessus
   * de 100 % ne serait jamais dépassé.
   */
  static BigDecimal round(BigDecimal value, String unit) {
    if (value == null) {
      return null;
    }
    double magnitude = Math.abs(value.doubleValue());
    BigDecimal rounded;
    if (magnitude < 10) {
      // Petites valeurs (une latence de 2,4 ms) : un chiffre après la virgule.
      rounded = value.setScale(1, RoundingMode.HALF_UP);
    } else if (magnitude < 1_000) {
      // Le gros des seuils : au multiple de 5 le plus proche.
      rounded =
          value
              .divide(BigDecimal.valueOf(5), 0, RoundingMode.HALF_UP)
              .multiply(BigDecimal.valueOf(5));
    } else {
      // Grands nombres : deux chiffres significatifs suffisent.
      int digits = (int) Math.floor(Math.log10(magnitude));
      BigDecimal step = BigDecimal.TEN.pow(Math.max(digits - 1, 0));
      rounded = value.divide(step, 0, RoundingMode.HALF_UP).multiply(step);
    }
    if ("%".equals(unit)) {
      return rounded.min(BigDecimal.valueOf(100)).max(BigDecimal.ZERO);
    }
    return rounded;
  }
}

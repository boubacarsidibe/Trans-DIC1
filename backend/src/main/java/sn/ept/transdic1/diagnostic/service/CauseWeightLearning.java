package sn.ept.transdic1.diagnostic.service;

import java.util.EnumMap;
import java.util.Map;
import sn.ept.transdic1.diagnostic.domain.CauseSource;

/**
 * Poids du diagnostic appris des confirmations de l'opérateur (M12, ADR 0045).
 *
 * <p>Les six poids de {@link CauseScoring} ont été fixés à la main, par raisonnement : un amont en
 * panne explique mieux qu'une anomalie. C'est défendable et c'était le bon choix pour démarrer,
 * mais cela reste une opinion. Quand l'opérateur confirme la vraie cause d'une alerte, il donne la
 * seule mesure qui compte : <i>cette source avait-elle raison ?</i>
 *
 * <p><b>Le modèle, et pourquoi il est si simple.</b> Le plan proposait une régression logistique.
 * Avec une seule variable explicative — la source de la cause —, une régression logistique revient
 * exactement au taux de réussite par source. Nous écrivons donc le taux, qui est le même modèle et
 * qui se lit : « sur les 23 fois où un amont en panne a été proposé, l'opérateur l'a retenu 19 fois
 * ». Un opérateur peut vérifier ce chiffre ; il ne pourra jamais vérifier un coefficient.
 *
 * <p><b>Le vrai problème, et la réponse.</b> Un taux sur trois confirmations ne vaut rien, et sur
 * un parc d'école il faudra des mois pour en avoir cent. Plutôt qu'un seuil brutal — « en dessous
 * de cinquante cas on garde les poids à la main, au-delà on les jette » —, le poids appris est
 * <b>tiré vers le poids à la main</b> d'autant plus fort qu'il y a peu de cas :
 *
 * <pre>
 * poids = (retenues + lissage × poids_a_la_main) / (propositions + lissage)
 * </pre>
 *
 * Sans aucune confirmation, le poids vaut exactement celui fixé à la main. Avec dix confirmations
 * contre un lissage de dix, il est à moitié chemin. Avec deux cents, l'opinion initiale ne pèse
 * plus rien. Il n'y a pas de bascule, donc pas de jour où le diagnostic change brutalement de
 * comportement sans que personne comprenne pourquoi.
 *
 * <p><b>Ce que les refus apportent.</b> Les propositions écartées comptent autant que celles
 * retenues : sans elles, une source proposée à chaque alerte paraîtrait excellente par le seul fait
 * d'être souvent là. C'est pourquoi une confirmation enregistre <i>toutes</i> les causes proposées,
 * et pas seulement la bonne.
 *
 * <p>Calcul pur, sans Spring ni base.
 */
public final class CauseWeightLearning {

  /**
   * Nombre de cas fictifs accordés à l'opinion initiale. À dix, il faut dix confirmations réelles
   * pour peser autant qu'elle — assez pour ne pas suivre les trois premières, assez peu pour que
   * cinquante cas tranchent.
   */
  public static final double DEFAULT_SMOOTHING = 10.0;

  /**
   * En dessous de ce total de confirmations, les poids appris ne sont pas employés du tout. Le
   * lissage suffirait à les rendre inoffensifs, mais afficher « poids appris » après deux
   * confirmations serait mentir sur ce que la plateforme sait.
   */
  public static final int MIN_CONFIRMATIONS = 20;

  private CauseWeightLearning() {}

  /**
   * Ce qu'on a compté pour une source.
   *
   * @param proposed fois où cette source a été proposée dans une alerte confirmée
   * @param chosen fois où l'opérateur l'a retenue comme la vraie cause
   */
  public record Tally(int proposed, int chosen) {

    /** Taux de réussite brut, sans lissage ; zéro faute de proposition. */
    public double rate() {
      return proposed == 0 ? 0 : (double) chosen / proposed;
    }
  }

  /**
   * Poids appris, tirés vers les poids fixés à la main.
   *
   * @param tallies comptes par source ; une source absente garde son poids à la main
   * @param smoothing nombre de cas fictifs accordés à l'opinion initiale
   * @return un poids par source, le plancher de décroissance restant celui d'origine — il décrit la
   *     durabilité d'une preuve, que les confirmations ne mesurent pas
   */
  public static Map<CauseSource, CauseScoring.Weight> weights(
      Map<CauseSource, Tally> tallies, double smoothing) {
    double shrink = Math.max(0.5, smoothing);
    var learned = new EnumMap<CauseSource, CauseScoring.Weight>(CauseSource.class);
    for (var entry : CauseScoring.WEIGHTS.entrySet()) {
      CauseSource source = entry.getKey();
      CauseScoring.Weight prior = entry.getValue();
      Tally tally = tallies == null ? null : tallies.get(source);
      if (tally == null || tally.proposed() == 0) {
        learned.put(source, prior);
        continue;
      }
      double weight = (tally.chosen() + shrink * prior.weight()) / (tally.proposed() + shrink);
      learned.put(source, new CauseScoring.Weight(bounded(weight), prior.floor()));
    }
    return java.util.Collections.unmodifiableMap(learned);
  }

  /** Confirmations distinctes représentées par ces comptes : le maximum des propositions. */
  public static int confirmations(Map<CauseSource, Tally> tallies) {
    if (tallies == null || tallies.isEmpty()) {
      return 0;
    }
    return tallies.values().stream().mapToInt(Tally::proposed).max().orElse(0);
  }

  /** Vrai si l'on en sait assez pour annoncer des poids appris. */
  public static boolean usable(Map<CauseSource, Tally> tallies, int minConfirmations) {
    return confirmations(tallies) >= Math.max(1, minConfirmations);
  }

  private static double bounded(double value) {
    if (Double.isNaN(value)) {
      return 0;
    }
    // Un poids reste une part : au-delà de 1 le score sortirait de [0, 1] et les comparaisons
    // entre causes perdraient leur sens.
    return Math.max(0.05, Math.min(1.0, value));
  }
}

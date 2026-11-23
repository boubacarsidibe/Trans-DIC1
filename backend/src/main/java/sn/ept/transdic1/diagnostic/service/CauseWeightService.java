package sn.ept.transdic1.diagnostic.service;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.diagnostic.domain.CauseSource;

/**
 * Poids employés par le diagnostic : ceux fixés à la main, corrigés par les confirmations de
 * l'opérateur quand il y en a assez (M12, ADR 0045).
 *
 * <p>Le calcul est dans {@link CauseWeightLearning}, qui l'explique. Ce service n'y ajoute que la
 * lecture des comptes en base et un cache : le diagnostic est calculé à la demande, parfois
 * plusieurs fois par minute, et relire les confirmations à chaque fois serait du gaspillage pour
 * une table qui ne bouge que lorsqu'un opérateur confirme une cause.
 *
 * <p>Le cache est vidé à chaque confirmation, pas rafraîchi sur minuterie : l'opérateur qui vient
 * de dire « c'était l'amont » doit voir l'effet de son geste, pas l'attendre.
 */
@Service
public class CauseWeightService {
  private static final Logger LOG = LoggerFactory.getLogger(CauseWeightService.class);

  private final JdbcTemplate jdbc;
  private final boolean enabled;
  private final double smoothing;
  private final int minConfirmations;
  private final AtomicReference<Snapshot> cache = new AtomicReference<>(null);

  public CauseWeightService(
      JdbcTemplate jdbc,
      @Value("${diagnostic.learning.enabled:true}") boolean enabled,
      @Value("${diagnostic.learning.smoothing:10.0}") double smoothing,
      @Value("${diagnostic.learning.min-confirmations:20}") int minConfirmations) {
    this.jdbc = jdbc;
    this.enabled = enabled;
    this.smoothing = Math.max(0.5, smoothing);
    this.minConfirmations = Math.max(1, minConfirmations);
  }

  /**
   * Les poids en vigueur et ce qui les justifie.
   *
   * @param weights poids employés pour classer les causes
   * @param learned vrai si les confirmations ont modifié les poids fixés à la main
   * @param confirmations nombre d'alertes dont la cause a été confirmée
   */
  public record Snapshot(
      Map<CauseSource, CauseScoring.Weight> weights, boolean learned, int confirmations) {

    /** Les poids fixés à la main, employés tant que personne n'a rien confirmé. */
    static Snapshot handSet() {
      return new Snapshot(CauseScoring.WEIGHTS, false, 0);
    }
  }

  /** Poids en vigueur, lus une fois puis gardés jusqu'à la prochaine confirmation. */
  @Transactional(readOnly = true)
  public Snapshot current() {
    Snapshot known = cache.get();
    if (known != null) {
      return known;
    }
    Snapshot computed = compute();
    cache.set(computed);
    return computed;
  }

  /** À appeler quand une confirmation a été enregistrée : le prochain diagnostic recalculera. */
  public void invalidate() {
    cache.set(null);
  }

  private Snapshot compute() {
    if (!enabled) {
      return Snapshot.handSet();
    }
    Map<CauseSource, CauseWeightLearning.Tally> tallies = tallies();
    if (!CauseWeightLearning.usable(tallies, minConfirmations)) {
      // Pas assez de confirmations : on garde les poids fixés à la main et on le dit. Le lissage
      // suffirait à rendre les poids appris inoffensifs, mais annoncer « poids appris » après deux
      // confirmations serait mentir sur ce que la plateforme sait.
      return new Snapshot(CauseScoring.WEIGHTS, false, CauseWeightLearning.confirmations(tallies));
    }
    Snapshot snapshot =
        new Snapshot(
            CauseWeightLearning.weights(tallies, smoothing),
            true,
            CauseWeightLearning.confirmations(tallies));
    LOG.info(
        "Poids du diagnostic appris de {} confirmations : {}",
        snapshot.confirmations(),
        snapshot.weights());
    return snapshot;
  }

  /** Propositions et choix par source, sur toutes les confirmations enregistrées. */
  private Map<CauseSource, CauseWeightLearning.Tally> tallies() {
    var tallies = new EnumMap<CauseSource, CauseWeightLearning.Tally>(CauseSource.class);
    jdbc.query(
        "SELECT source, count(*), count(*) FILTER (WHERE chosen) FROM cause_feedback"
            + " GROUP BY source",
        (org.springframework.jdbc.core.RowCallbackHandler)
            rs -> {
              try {
                tallies.put(
                    CauseSource.valueOf(rs.getString(1)),
                    new CauseWeightLearning.Tally(rs.getInt(2), rs.getInt(3)));
              } catch (IllegalArgumentException e) {
                // Une source retirée du code mais encore en base : on l'ignore plutôt que de
                // faire échouer tout le diagnostic.
                LOG.debug("Source de cause inconnue en base : {}", rs.getString(1));
              }
            });
    return tallies;
  }
}

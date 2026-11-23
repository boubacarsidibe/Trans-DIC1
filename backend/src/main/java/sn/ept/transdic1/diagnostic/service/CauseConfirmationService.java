package sn.ept.transdic1.diagnostic.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import sn.ept.transdic1.diagnostic.domain.CauseFeedback;
import sn.ept.transdic1.diagnostic.domain.CauseSource;
import sn.ept.transdic1.diagnostic.dto.DiagnosticResponse;
import sn.ept.transdic1.diagnostic.dto.DiagnosticResponse.CauseProbable;
import sn.ept.transdic1.diagnostic.repository.CauseFeedbackRepository;

/**
 * Enregistre la cause que l'opérateur a reconnue comme la vraie (M12, ADR 0045).
 *
 * <p>Une confirmation enregistre <b>toutes les causes proposées</b> au moment du geste, et non
 * seulement celle retenue. Sans les propositions écartées, on ne saurait pas distinguer « cette
 * source a raison neuf fois sur dix » de « cette source est proposée neuf fois sur dix » : ce sont
 * les refus qui portent l'information.
 *
 * <p>Répondre « aucune de ces causes » est accepté et précieux : cela enregistre autant de refus
 * qu'il y avait de propositions, et dit que le diagnostic s'est trompé sur toute la ligne.
 *
 * <p>Reconfirmer une alerte <b>remplace</b> l'avis précédent au lieu de l'empiler : l'opérateur a
 * le droit de se corriger, et deux avis contradictoires de la même personne sur la même alerte ne
 * seraient pas deux observations.
 */
@Service
public class CauseConfirmationService {
  private static final Logger LOG = LoggerFactory.getLogger(CauseConfirmationService.class);

  private final DiagnosticService diagnostics;
  private final CauseFeedbackRepository feedback;
  private final CauseWeightService weights;
  private final Clock clock;

  public CauseConfirmationService(
      DiagnosticService diagnostics,
      CauseFeedbackRepository feedback,
      CauseWeightService weights,
      Clock clock) {
    this.diagnostics = diagnostics;
    this.feedback = feedback;
    this.weights = weights;
    this.clock = clock;
  }

  /** Ce que la confirmation a enregistré. */
  public record Outcome(int recorded, CauseSource chosen, int confirmations) {}

  /**
   * Enregistre le verdict de l'opérateur sur les causes proposées pour une alerte.
   *
   * <p>Les propositions sont relues par le diagnostic au moment de la confirmation, et non envoyées
   * par l'écran : le serveur ne doit pas croire le client sur ce qu'il a affiché. La contrepartie
   * est assumée et dite dans l'ADR — si les faits ont changé entre l'affichage et le clic, la liste
   * enregistrée est celle de l'instant du clic.
   *
   * @param chosen source retenue, ou null pour « aucune de ces causes »
   * @throws ResponseStatusException 404 si l'alerte n'existe pas ou est hors du périmètre, 409 si
   *     le diagnostic ne propose aucune cause, ou si la source retenue n'en fait pas partie
   */
  @Transactional
  public Outcome confirm(UUID alertId, CauseSource chosen, String by) {
    DiagnosticResponse diagnostic = diagnostics.diagnose(alertId);
    List<CauseProbable> proposed = diagnostic.causesProbables();
    if (proposed.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Le diagnostic ne propose aucune cause pour cette alerte : il n'y a rien à confirmer");
    }
    if (chosen != null && proposed.stream().noneMatch(cause -> cause.source() == chosen)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Cette cause n'est pas proposée pour cette alerte : confirmez l'une des causes"
              + " affichées, ou répondez qu'aucune ne convient");
    }
    // Reconfirmer remplace : l'opérateur a le droit de se corriger. Le vidage est indispensable :
    // sans lui, la couche de persistance ordonne les insertions avant la suppression, qui efface
    // alors les lignes qu'on vient d'écrire — ou bute sur la contrainte d'unicité.
    feedback.deleteByAlertId(alertId);
    feedback.flush();
    Instant now = clock.instant();
    for (CauseProbable cause : proposed) {
      feedback.save(
          new CauseFeedback(
              alertId,
              cause.source(),
              strength(cause, diagnostic),
              minutes(cause, diagnostic.ouverture()),
              cause.source() == chosen,
              now,
              by));
    }
    // Les comptes sont relus en SQL : il faut que les lignes soient écrites avant de vider le
    // cache, sinon le recalcul ne verrait rien et l'opérateur croirait son geste perdu.
    feedback.flush();
    weights.invalidate();
    LOG.info(
        "Cause confirmée sur l'alerte {} par {} : {}",
        alertId,
        by,
        chosen == null ? "aucune des causes proposées" : chosen);
    return new Outcome(proposed.size(), chosen, weights.current().confirmations());
  }

  /** Causes déjà confirmées pour une alerte, pour que l'écran montre l'avis enregistré. */
  @Transactional(readOnly = true)
  public List<CauseFeedback> recorded(UUID alertId) {
    return feedback.findByAlertId(alertId);
  }

  /**
   * Force de la preuve telle que le score la reflète. Le score rendu par l'API est le produit du
   * poids, de la force et de la décroissance ; la force seule n'y est pas exposée. On la
   * reconstitue en divisant, ce qui suffit pour conserver une trace exploitable : si la division
   * n'a pas de sens (poids ou décroissance nuls), on garde le score, borné.
   */
  private static double strength(CauseProbable cause, DiagnosticResponse diagnostic) {
    double weight = CauseScoring.WEIGHTS.get(cause.source()).weight();
    double decay = CauseScoring.decay(cause.source(), cause.survenueLe(), diagnostic.ouverture());
    double divisor = weight * decay;
    double value = divisor <= 0 ? cause.score() : cause.score() / divisor;
    return Math.max(0, Math.min(1, value));
  }

  private static Double minutes(CauseProbable cause, Instant opened) {
    if (cause.survenueLe() == null || opened == null) {
      return null;
    }
    return Math.abs(Duration.between(opened, cause.survenueLe()).toMillis()) / 60_000.0;
  }
}

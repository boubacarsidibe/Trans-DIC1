package sn.ept.transdic1.alerting.service;

import java.time.Duration;

/**
 * Budget d'erreur d'un service (M8, ADR 0044) : combien d'indisponibilité son objectif lui permet
 * encore, et à quelle vitesse il la dépense.
 *
 * <p><b>Pourquoi un budget plutôt qu'un pourcentage.</b> « Le service est à 99,2 % ce mois-ci » ne
 * dit pas s'il faut s'inquiéter. « Il a dépensé 160 % de son budget d'indisponibilité » le dit, et
 * surtout « il en a dépensé un tiers dans la dernière heure » le dit <i>avant</i> que le mois soit
 * perdu. C'est toute la différence entre constater et prévenir.
 *
 * <p>Un objectif de 99,5 % sur trente jours autorise 0,5 % d'indisponibilité, soit trois heures et
 * demie. C'est le budget. Le <b>taux de consommation</b> est la vitesse à laquelle il part, mesurée
 * sur une fenêtre courte et rapportée à la vitesse qui le ferait durer exactement un mois : à 1, le
 * budget tient tout le mois ; à 14,4, il est épuisé en deux jours.
 *
 * <p>Le chiffre 14,4 n'est pas arbitraire : c'est le taux auquel une heure consomme 2 % du budget
 * d'un mois, le seuil d'alerte rapide retenu par les pratiques publiées de fiabilité (Google SRE).
 * Il laisse passer les incidents courts — personne ne doit être réveillé pour trois minutes de
 * coupure — et attrape ceux qui, s'ils durent, mangeront le mois.
 *
 * <p>Calcul pur, sans Spring ni base.
 */
public final class ErrorBudget {

  /**
   * Taux de consommation au-delà duquel une heure met le mois en danger : à 14,4, une heure
   * consomme 2 % du budget mensuel. Valeur des pratiques publiées de fiabilité.
   */
  public static final double FAST_BURN = 14.4;

  private ErrorBudget() {}

  /**
   * État du budget sur la fenêtre d'objectif.
   *
   * @param target objectif de disponibilité, entre 0 et 1 (0,995 pour 99,5 %)
   * @param samples relevés de sonde sur la fenêtre
   * @param failed relevés en échec
   * @param window durée de la fenêtre d'objectif
   */
  public record Status(double target, long samples, long failed, Duration window) {

    /**
     * Disponibilité observée, entre 0 et 1 ; 1 faute de relevé (on ne reproche rien au silence).
     */
    public double availability() {
      return samples == 0 ? 1 : (double) (samples - failed) / samples;
    }

    /** Part du budget déjà dépensée : 1 veut dire épuisé, au-delà l'objectif est manqué. */
    public double consumed() {
      double budget = 1 - target;
      if (budget <= 0) {
        // Un objectif de 100 % n'a pas de budget : le moindre échec le manque.
        return failed == 0 ? 0 : Double.POSITIVE_INFINITY;
      }
      return (1 - availability()) / budget;
    }

    /** Budget restant, en minutes d'indisponibilité ; zéro s'il est épuisé. */
    public long remainingMinutes() {
      double remaining = (1 - consumed()) * (1 - target) * window.toMinutes();
      return remaining <= 0 ? 0 : Math.round(remaining);
    }

    /** Vrai si l'objectif est manqué sur la fenêtre. */
    public boolean exhausted() {
      return consumed() >= 1;
    }

    /** Assez de relevés pour que le chiffre veuille dire quelque chose. */
    public boolean measurable() {
      return samples >= MIN_SAMPLES;
    }
  }

  /** En dessous, la disponibilité observée n'est qu'un reflet de trois pings. */
  public static final int MIN_SAMPLES = 60;

  /**
   * Taux de consommation sur une fenêtre courte, rapporté à la vitesse soutenable.
   *
   * <p>À 1, le budget durerait exactement la fenêtre d'objectif. À 14,4, il est épuisé en deux
   * jours. Zéro si la fenêtre courte n'a pas assez de relevés : un taux calculé sur deux pings
   * monterait à 400 au premier échec.
   *
   * @param samples relevés sur la fenêtre courte
   * @param failed relevés en échec sur la fenêtre courte
   * @param target objectif de disponibilité
   * @param minSamples relevés minimaux pour calculer un taux
   */
  public static double burnRate(long samples, long failed, double target, int minSamples) {
    if (samples < Math.max(2, minSamples) || target >= 1) {
      return 0;
    }
    double failureRate = (double) failed / samples;
    double budget = 1 - target;
    return failureRate / budget;
  }

  /** Taux de consommation en mots, pour un message d'alerte. */
  public static String describeBurn(double burnRate) {
    if (burnRate >= 100) {
      return "plus de cent fois";
    }
    if (burnRate >= 10) {
      return Math.round(burnRate) + " fois";
    }
    return String.format(java.util.Locale.FRANCE, "%.1f fois", burnRate);
  }

  /** Budget total, en minutes d'indisponibilité autorisées sur la fenêtre. */
  public static long budgetMinutes(double target, Duration window) {
    return Math.round((1 - target) * window.toMinutes());
  }
}

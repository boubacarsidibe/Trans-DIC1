package sn.ept.transdic1.alerting.service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import sn.ept.transdic1.alerting.service.CapacityForecast.Point;
import sn.ept.transdic1.alerting.service.CapacityForecast.Result;
import sn.ept.transdic1.alerting.service.CapacityForecast.Settings;

/**
 * Choix entre la droite (ADR 0020) et le cycle journalier (ADR 0036), par épreuve sur les dernières
 * heures.
 *
 * <p>Il n'y a pas de bonne méthode dans l'absolu : un disque monte sans se soucier de l'heure, la
 * mémoire d'un serveur d'application suit la journée des usagers. Plutôt que de deviner d'après le
 * nom de l'indicateur, on met les deux à l'épreuve : chacune est ajustée sur tout l'historique
 * <b>sauf les dernières 24 heures</b>, puis on compare ce qu'elles avaient prédit pour ces
 * heures-là à ce qui s'est réellement passé. La plus juste l'emporte.
 *
 * <p>C'est la seule façon honnête de trancher, et elle se lit : l'écran peut dire « modèle
 * saisonnier, erreur de 1,2 point contre 4,8 pour la droite ».
 */
public final class ForecastModels {
  /** Heures mises de côté pour l'épreuve : une journée, soit un cycle entier. */
  static final int HOLD_OUT_HOURS = 24;

  /**
   * Écart d'erreur en deçà duquel on garde la droite. Le modèle saisonnier doit faire nettement
   * mieux pour être retenu : il a plus de paramètres, donc plus d'occasions de coller au passé sans
   * rien prévoir du futur.
   */
  static final double MARGIN = 0.9;

  private ForecastModels() {}

  /** Ce qu'a donné l'épreuve, pour l'afficher. */
  public record Comparison(Double linearError, Double seasonalError) {}

  /** Prévision retenue, droite ou cycle journalier selon l'épreuve. */
  public static Result choose(List<Point> points, Instant now, Settings settings) {
    Result linear = CapacityForecast.compute(points, now, settings);
    Result seasonal = SeasonalForecast.compute(points, now, settings);
    if (seasonal == null) {
      return linear;
    }
    Comparison comparison = compare(points, now, settings);
    if (comparison.seasonalError() == null || comparison.linearError() == null) {
      // Pas de quoi éprouver les deux : la droite, plus simple, reste le choix par défaut.
      return linear;
    }
    return comparison.seasonalError() < comparison.linearError() * MARGIN ? seasonal : linear;
  }

  /** Erreur moyenne absolue de chaque méthode sur les dernières 24 heures. */
  public static Comparison compare(List<Point> input, Instant now, Settings settings) {
    List<Point> points = input.stream().sorted(Comparator.comparing(Point::at)).toList();
    return new Comparison(
        linearHoldOutError(points, now, settings),
        SeasonalForecast.holdOutError(points, HOLD_OUT_HOURS));
  }

  /**
   * Erreur de la droite sur les dernières heures : ajustée sans elles, puis comparée à ce qui s'est
   * passé.
   */
  static Double linearHoldOutError(List<Point> points, Instant now, Settings settings) {
    if (points.size() < HOLD_OUT_HOURS + settings.minPoints()) {
      return null;
    }
    List<Point> training = points.subList(0, points.size() - HOLD_OUT_HOURS);
    List<Point> held = points.subList(points.size() - HOLD_OUT_HOURS, points.size());
    Instant fitEnd = training.get(training.size() - 1).at();
    Result fit = CapacityForecast.compute(training, fitEnd, settings);
    if (fit.slopePerDay() == null || fit.intercept() == null) {
      return null;
    }
    double total = 0;
    for (Point point : held) {
      double days = (point.at().toEpochMilli() - fitEnd.toEpochMilli()) / 86_400_000.0;
      total += Math.abs(point.value() - (fit.intercept() + fit.slopePerDay() * days));
    }
    return total / held.size();
  }
}

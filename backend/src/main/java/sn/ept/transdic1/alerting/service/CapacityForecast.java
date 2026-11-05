package sn.ept.transdic1.alerting.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import sn.ept.transdic1.alerting.domain.ForecastStatus;

/**
 * Prévision de saturation par régression linéaire (moindres carrés ordinaires), sans Spring ni base
 * (ADR 0020). Le temps est exprimé en jours par rapport à l'instant de calcul : la pente se lit en
 * points par jour et l'ordonnée à l'origine est la valeur que la droite donne « maintenant ».
 *
 * <p>Jours restants = (limite - min(ordonnée, dernière mesure)) / pente : une baisse récente (un
 * nettoyage) éloigne aussitôt la saturation. La méthode est volontairement simple : linéaire, sans
 * saisonnalité ; le coefficient de détermination r² dit à quel point la droite colle aux mesures.
 */
public final class CapacityForecast {
  /** En dessous de cette pente (points par jour), la série est considérée sans croissance. */
  static final double MIN_SLOPE_PER_DAY = 1e-3;

  private static final double MILLIS_PER_DAY = 86_400_000.0;

  private CapacityForecast() {}

  /** Une mesure (moyenne horaire en pratique). */
  public record Point(Instant at, double value) {}

  /**
   * Réglages : limite (100 pour un pourcentage), période minimale couverte entre le premier et le
   * dernier point, nombre minimal de points.
   */
  public record Settings(double limit, Duration minSpan, int minPoints) {
    public static Settings defaults() {
      return new Settings(100.0, Duration.ofDays(3), 24);
    }
  }

  /**
   * Résultat. {@code current} est la valeur du point le plus récent ; pente, ordonnée et r² sont
   * nuls faute de données ; {@code daysToLimit} et {@code projectedAt} ne sont renseignés que pour
   * {@code OK} et {@code ALREADY_FULL} (0 jour).
   */
  public record Result(
      ForecastStatus status,
      Double current,
      Double slopePerDay,
      Double intercept,
      Double r2,
      Double daysToLimit,
      Instant projectedAt,
      double limit,
      int points,
      Instant firstAt,
      Instant lastAt) {}

  public static Result compute(List<Point> input, Instant now, Settings settings) {
    List<Point> points = input.stream().sorted(Comparator.comparing(Point::at)).toList();
    int n = points.size();
    if (n == 0) {
      return new Result(
          ForecastStatus.NOT_ENOUGH_DATA,
          null,
          null,
          null,
          null,
          null,
          null,
          settings.limit(),
          0,
          null,
          null);
    }
    Instant first = points.get(0).at();
    Instant last = points.get(n - 1).at();
    double current = points.get(n - 1).value();
    boolean enough =
        n >= Math.max(2, settings.minPoints())
            && Duration.between(first, last).compareTo(settings.minSpan()) >= 0;

    Double slope = null;
    Double intercept = null;
    Double r2 = null;
    if (n >= 2) {
      double[] fit = fit(points, now);
      if (fit != null) {
        slope = fit[0];
        intercept = fit[1];
        r2 = fit[2];
      }
    }

    if (current >= settings.limit()) {
      return new Result(
          ForecastStatus.ALREADY_FULL,
          current,
          enough ? slope : null,
          enough ? intercept : null,
          enough ? r2 : null,
          0.0,
          now,
          settings.limit(),
          n,
          first,
          last);
    }
    if (!enough || slope == null) {
      return new Result(
          ForecastStatus.NOT_ENOUGH_DATA,
          current,
          null,
          null,
          null,
          null,
          null,
          settings.limit(),
          n,
          first,
          last);
    }
    if (slope <= MIN_SLOPE_PER_DAY) {
      return new Result(
          ForecastStatus.NO_GROWTH,
          current,
          slope,
          intercept,
          r2,
          null,
          null,
          settings.limit(),
          n,
          first,
          last);
    }
    // Les jours partent de la plus basse des deux valeurs « actuelles » : celle de la droite et la
    // dernière mesure. Après un nettoyage (disque revenu à 30 %), la droite tirée par des jours de
    // hausse dirait encore « plein dans quelques jours » ; la mesure observée l'emporte.
    double from = Math.min(current, intercept);
    double days = Math.max(0.0, (settings.limit() - from) / slope);
    Instant projected = now.plusMillis(Math.round(days * MILLIS_PER_DAY));
    return new Result(
        ForecastStatus.OK,
        current,
        slope,
        intercept,
        r2,
        days,
        projected,
        settings.limit(),
        n,
        first,
        last);
  }

  /**
   * Moindres carrés ordinaires : {pente, ordonnée à l'instant {@code now}, r²}, ou null si tous les
   * points sont au même instant. Sommes centrées sur les moyennes pour la stabilité numérique.
   */
  private static double[] fit(List<Point> points, Instant now) {
    int n = points.size();
    double meanX = 0;
    double meanY = 0;
    double[] xs = new double[n];
    for (int i = 0; i < n; i++) {
      xs[i] = (points.get(i).at().toEpochMilli() - now.toEpochMilli()) / MILLIS_PER_DAY;
      meanX += xs[i];
      meanY += points.get(i).value();
    }
    meanX /= n;
    meanY /= n;
    double sxx = 0;
    double sxy = 0;
    double syy = 0;
    for (int i = 0; i < n; i++) {
      double dx = xs[i] - meanX;
      double dy = points.get(i).value() - meanY;
      sxx += dx * dx;
      sxy += dx * dy;
      syy += dy * dy;
    }
    if (sxx == 0) {
      return null;
    }
    double slope = sxy / sxx;
    double intercept = meanY - slope * meanX;
    double ssRes = 0;
    for (int i = 0; i < n; i++) {
      double residual = points.get(i).value() - (intercept + slope * xs[i]);
      ssRes += residual * residual;
    }
    // Série parfaitement plate : rien à expliquer, r² sans objet (0).
    double r2 = syy == 0 ? 0.0 : Math.max(0.0, 1 - ssRes / syy);
    return new double[] {slope, intercept, r2};
  }

  /** Libellé de confiance : haute (r² ≥ 0,8), moyenne (≥ 0,5), faible en dessous. */
  public static String confidence(Double r2) {
    if (r2 == null) {
      return null;
    }
    if (r2 >= 0.8) {
      return "haute";
    }
    return r2 >= 0.5 ? "moyenne" : "faible";
  }
}

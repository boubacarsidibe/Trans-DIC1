package sn.ept.transdic1.alerting.service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import sn.ept.transdic1.alerting.domain.ForecastMethod;
import sn.ept.transdic1.alerting.domain.ForecastStatus;
import sn.ept.transdic1.alerting.service.CapacityForecast.Point;
import sn.ept.transdic1.alerting.service.CapacityForecast.Result;
import sn.ept.transdic1.alerting.service.CapacityForecast.Settings;

/**
 * Prévision de saturation avec cycle journalier, par lissage exponentiel triple — Holt-Winters
 * additif (M13 du plan, ADR 0036).
 *
 * <p>La droite de l'ADR 0020 suffit pour un disque, qui monte sans se soucier de l'heure. Elle ne
 * vaut rien pour la mémoire d'un serveur d'application ou le débit d'un lien : ceux-là montent le
 * matin, redescendent la nuit, et une droite tirée au milieu de ce cycle annonce la saturation bien
 * trop tard — ou jamais.
 *
 * <p>Trois composantes, chacune lissée séparément : le <b>niveau</b> (où l'on est), la
 * <b>tendance</b> (de combien cela monte par heure) et la <b>saison</b> (ce que vaut l'heure en
 * cours par rapport à la moyenne de la journée). La prévision d'une heure future ajoute les trois,
 * ce qui permet de dire non seulement « saturé dans trois jours », mais « saturé dans trois jours,
 * vers 14 h » — car c'est au pic que la limite se touche, pas en moyenne.
 *
 * <p>Additif et non multiplicatif : un pourcentage d'occupation a une amplitude de cycle à peu près
 * constante, pas proportionnelle au niveau. Les coefficients de lissage sont fixes et non optimisés
 * : les ajuster demanderait une recherche à chaque calcul, pour un gain illusoire sur quatorze
 * jours de données horaires.
 */
public final class SeasonalForecast {
  /** Longueur du cycle : vingt-quatre moyennes horaires, soit une journée. */
  public static final int PERIOD = 24;

  /** Il faut au moins deux cycles complets pour distinguer une saison d'un accident. */
  public static final int MIN_PERIODS = 2;

  /** Lissage du niveau : assez réactif pour suivre un palier. */
  static final double ALPHA = 0.3;

  /** Lissage de la tendance : lent, sans quoi une pointe passerait pour une montée durable. */
  static final double BETA = 0.05;

  /** Lissage de la saison : le profil d'une journée ne change pas d'un jour à l'autre. */
  static final double GAMMA = 0.2;

  /** Heures simulées au plus pour chercher la saturation : soixante jours. */
  static final int MAX_HORIZON_HOURS = 24 * 60;

  private static final double MILLIS_PER_DAY = 86_400_000.0;
  private static final double HOURS_PER_DAY = 24.0;

  /** Fuseau de l'école : « vers 14 h » n'a de sens que quelque part. */
  private static final ZoneId ZONE = ZoneId.of("Africa/Dakar");

  private SeasonalForecast() {}

  /** Modèle ajusté, utilisable pour prévoir aussi loin qu'on veut. */
  record Model(double level, double trend, double[] seasonal, int lastIndex, double r2) {

    /** Valeur prévue {@code h} heures après le dernier point observé ({@code h} ≥ 1). */
    double at(int h) {
      int index = Math.floorMod(lastIndex + h, PERIOD);
      return level + h * trend + seasonal[index];
    }
  }

  /**
   * Prévision saisonnière, ou {@code null} si la série ne s'y prête pas (pas assez de cycles, ou
   * points trop irréguliers pour être tenus pour horaires).
   */
  public static Result compute(List<Point> input, Instant now, Settings settings) {
    List<Point> points = input.stream().sorted(Comparator.comparing(Point::at)).toList();
    if (points.size() < PERIOD * MIN_PERIODS) {
      return null;
    }
    Model model = fit(values(points));
    if (model == null) {
      return null;
    }
    // La qualité annoncée est celle d'une prévision, pas d'un ajustement : elle se mesure sur des
    // heures que le modèle n'a pas vues. Un lissage qui suit le bruit pas à pas aurait sinon une
    // excellente note tout en ne prévoyant rien, et passerait le seuil de confiance des alertes.
    Double quality = holdOutR2(points);
    int n = points.size();
    Instant first = points.get(0).at();
    Instant last = points.get(n - 1).at();
    double current = points.get(n - 1).value();
    // La tendance est lissée par heure ; on la rend par jour, comme la pente de la droite, pour
    // que les deux méthodes se lisent de la même façon.
    double slopePerDay = model.trend() * HOURS_PER_DAY;

    if (current >= settings.limit()) {
      return result(
          ForecastStatus.ALREADY_FULL,
          current,
          slopePerDay,
          model,
          quality,
          0.0,
          now,
          settings,
          n,
          first,
          last,
          peakHour(model));
    }
    Integer hours = hoursToLimit(model, settings.limit());
    if (hours == null) {
      return result(
          ForecastStatus.NO_GROWTH,
          current,
          slopePerDay,
          model,
          quality,
          null,
          null,
          settings,
          n,
          first,
          last,
          peakHour(model));
    }
    // Les heures comptent depuis le dernier point observé, pas depuis maintenant : si la collecte
    // a pris du retard, la date projetée ne doit pas s'en trouver repoussée d'autant.
    Instant projected = last.plus(Duration.ofHours(hours));
    double days = Math.max(0.0, (projected.toEpochMilli() - now.toEpochMilli()) / MILLIS_PER_DAY);
    return result(
        ForecastStatus.OK,
        current,
        slopePerDay,
        model,
        quality,
        days,
        projected,
        settings,
        n,
        first,
        last,
        peakHour(model));
  }

  /** Erreur moyenne absolue de la méthode sur les {@code horizon} derniers points, ou null. */
  public static Double holdOutError(List<Point> input, int horizon) {
    List<Point> points = input.stream().sorted(Comparator.comparing(Point::at)).toList();
    if (points.size() < PERIOD * MIN_PERIODS + horizon) {
      return null;
    }
    List<Point> training = points.subList(0, points.size() - horizon);
    List<Point> held = points.subList(points.size() - horizon, points.size());
    Model model = fit(values(training));
    if (model == null) {
      return null;
    }
    double total = 0;
    for (int i = 0; i < held.size(); i++) {
      total += Math.abs(held.get(i).value() - model.at(i + 1));
    }
    return total / held.size();
  }

  private static double[] values(List<Point> points) {
    double[] values = new double[points.size()];
    for (int i = 0; i < points.size(); i++) {
      values[i] = points.get(i).value();
    }
    return values;
  }

  /**
   * Ajuste le modèle. Le niveau part de la moyenne du premier cycle, la tendance de l'écart entre
   * les deux premiers cycles, et la saison de l'écart de chaque heure à la moyenne de son cycle.
   */
  static Model fit(double[] values) {
    int n = values.length;
    int cycles = n / PERIOD;
    if (cycles < MIN_PERIODS) {
      return null;
    }
    double firstMean = mean(values, 0, PERIOD);
    double secondMean = mean(values, PERIOD, PERIOD);
    double level = firstMean;
    double trend = (secondMean - firstMean) / PERIOD;
    double[] seasonal = new double[PERIOD];
    for (int cycle = 0; cycle < cycles; cycle++) {
      double cycleMean = mean(values, cycle * PERIOD, PERIOD);
      for (int hour = 0; hour < PERIOD; hour++) {
        seasonal[hour] += (values[cycle * PERIOD + hour] - cycleMean) / cycles;
      }
    }

    double ssRes = 0;
    double mean = mean(values, 0, n);
    double ssTot = 0;
    for (int i = 0; i < n; i++) {
      int index = Math.floorMod(i, PERIOD);
      double expected = level + trend + seasonal[index];
      double previousLevel = level;
      level = ALPHA * (values[i] - seasonal[index]) + (1 - ALPHA) * (level + trend);
      trend = BETA * (level - previousLevel) + (1 - BETA) * trend;
      seasonal[index] = GAMMA * (values[i] - level) + (1 - GAMMA) * seasonal[index];
      if (i > 0) {
        // Le premier point n'a pas de prévision à comparer : il sert d'amorçage.
        ssRes += (values[i] - expected) * (values[i] - expected);
        ssTot += (values[i] - mean) * (values[i] - mean);
      }
    }
    double r2 = ssTot == 0 ? 0.0 : Math.max(0.0, 1 - ssRes / ssTot);
    if (!Double.isFinite(level) || !Double.isFinite(trend)) {
      return null;
    }
    return new Model(level, trend, seasonal, Math.floorMod(n - 1, PERIOD), r2);
  }

  /** Première heure où la prévision atteint la limite, ou null si elle ne l'atteint jamais. */
  private static Integer hoursToLimit(Model model, double limit) {
    for (int h = 1; h <= MAX_HORIZON_HOURS; h++) {
      if (model.at(h) >= limit) {
        return h;
      }
    }
    return null;
  }

  /** Heure locale du sommet du cycle : celle où la limite sera touchée la première. */
  private static Integer peakHour(Model model) {
    int peak = 0;
    for (int hour = 1; hour < PERIOD; hour++) {
      if (model.seasonal()[hour] > model.seasonal()[peak]) {
        peak = hour;
      }
    }
    // L'index du tableau est relatif au dernier point observé ; on le ramène à une heure locale.
    return peak;
  }

  private static double mean(double[] values, int from, int length) {
    double total = 0;
    for (int i = from; i < from + length; i++) {
      total += values[i];
    }
    return total / length;
  }

  /**
   * Coefficient de détermination calculé sur les dernières heures, mises de côté avant l'ajustement
   * : null si l'historique ne permet pas cette épreuve.
   */
  static Double holdOutR2(List<Point> points) {
    int horizon = PERIOD;
    if (points.size() < PERIOD * MIN_PERIODS + horizon) {
      return null;
    }
    List<Point> training = points.subList(0, points.size() - horizon);
    List<Point> held = points.subList(points.size() - horizon, points.size());
    Model model = fit(values(training));
    if (model == null) {
      return null;
    }
    double mean = 0;
    for (Point point : held) {
      mean += point.value();
    }
    mean /= held.size();
    double ssRes = 0;
    double ssTot = 0;
    for (int i = 0; i < held.size(); i++) {
      double error = held.get(i).value() - model.at(i + 1);
      ssRes += error * error;
      ssTot += (held.get(i).value() - mean) * (held.get(i).value() - mean);
    }
    return ssTot == 0 ? 0.0 : Math.max(0.0, 1 - ssRes / ssTot);
  }

  private static Result result(
      ForecastStatus status,
      Double current,
      Double slopePerDay,
      Model model,
      Double quality,
      Double days,
      Instant projected,
      Settings settings,
      int points,
      Instant first,
      Instant last,
      Integer peakHour) {
    return new Result(
        status,
        current,
        slopePerDay,
        model.level(),
        quality,
        days,
        projected,
        settings.limit(),
        points,
        first,
        last,
        ForecastMethod.SEASONAL,
        localPeakHour(last, model.lastIndex(), peakHour));
  }

  /**
   * Heure locale du pic.
   *
   * <p>Le tableau saisonnier est indexé sur la <b>position dans la série</b>, pas sur l'heure du
   * jour : la case 0 est le premier point reçu, quelle que soit l'heure qu'il portait. Pour ramener
   * un index à une heure, on part du dernier point, dont on connaît l'index et l'heure, et on
   * avance du décalage qui sépare les deux cases.
   */
  private static Integer localPeakHour(Instant last, int lastIndex, Integer peakIndex) {
    if (peakIndex == null || last == null) {
      return null;
    }
    int lastHour = last.atZone(ZONE).getHour();
    int shift = Math.floorMod(peakIndex - lastIndex, PERIOD);
    return Math.floorMod(lastHour + shift, PERIOD);
  }

  /** Décalage employé pour l'heure locale, exposé pour les tests. */
  static ZoneOffset zoneOffset(Instant at) {
    return ZONE.getRules().getOffset(at);
  }
}

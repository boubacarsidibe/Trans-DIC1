package sn.ept.transdic1.alerting.service;

import java.util.Arrays;

/**
 * Rupture de niveau (M5 du plan, ADR 0038) : repère le moment où une mesure a <b>changé de régime
 * et y est restée</b> — un lien qui passe de 1 Gb/s à 100 Mb/s, une mémoire qui ne redescend plus.
 *
 * <p><b>Pourquoi la détection par point n'y suffit pas.</b> Celle de l'ADR 0015 compare la valeur
 * du moment à l'habitude du même créneau. Au moment du changement, elle crie ; quelques jours plus
 * tard, le nouveau niveau <i>est</i> l'habitude et plus rien ne crie, alors que l'équipement n'est
 * jamais revenu. Si personne n'a vu l'alerte au bon quart d'heure, elle est perdue. Et un
 * changement qui s'installe doucement — une fuite mémoire qui gagne un point par jour — ne dépasse
 * jamais de seuil : chaque heure ressemble à la précédente.
 *
 * <p><b>Le CUSUM.</b> On ne regarde pas les valeurs mais leur <i>somme cumulée</i> d'écarts à la
 * normale. Du bruit se compense : la somme reste autour de zéro. Un décalage, même petit, pousse
 * toujours dans le même sens, et la somme s'éloigne sans retour. C'est ce qui permet de voir un
 * décalage d'un seul écart-type, qu'aucun seuil ne verrait, et de dire quand il a commencé : en
 * revenant au dernier instant où la somme valait encore zéro.
 *
 * <p>Deux sommes sont tenues, une pour la hausse et une pour la baisse, chacune repoussée vers zéro
 * d'un <i>drift</i> ({@code drift}) à chaque pas — sans quoi elles dériveraient sur du bruit. Au
 * delà de la limite ({@code limit}), le signal est donné. Ce sont les deux réglages classiques du
 * CUSUM tabulaire ; les valeurs par défaut, 0,5 et 5, viennent de la littérature de maîtrise
 * statistique des procédés et détectent un décalage d'un écart-type en une dizaine de pas.
 *
 * <p><b>Ce que le CUSUM seul ne dit pas.</b> Il signale une accumulation, pas une durée : un
 * épisode de deux heures le déclenche aussi. Trois conditions s'y ajoutent donc, et c'est ce qui
 * distingue cette détection d'un détecteur de pointe : le nouveau niveau doit durer ({@code
 * minAfter}), être franchement différent de l'ancien ({@code minShift} écarts robustes et {@code
 * minRelative} en relatif), et <b>tenir encore maintenant</b> — un pic passé laisse la série
 * revenue, et rien ne s'ouvre.
 *
 * <p>Calcul pur, sans Spring ni base : il se vérifie sur des séries écrites à la main.
 */
public final class LevelShift {

  /** Part du niveau d'avant servant de plancher à l'écart robuste, pour une série sans bruit. */
  private static final double FLAT_FLOOR = 0.01;

  private LevelShift() {}

  /**
   * Réglages de la détection.
   *
   * @param drift repoussée des sommes vers zéro à chaque pas, en écarts robustes (0,5 classique)
   * @param limit somme au-delà de laquelle le signal est donné (5 classique)
   * @param minAfter heures que le nouveau niveau doit avoir tenu pour être une rupture
   * @param minShift écart minimal entre les deux niveaux, en écarts robustes
   * @param minRelative écart minimal en proportion du plus grand des deux niveaux
   */
  public record Settings(
      double drift, double limit, int minAfter, double minShift, double minRelative) {

    /** Réglages par défaut. */
    public static Settings defaults() {
      return new Settings(0.5, 5, 6, 2.0, 0.05);
    }
  }

  /**
   * Une rupture constatée.
   *
   * @param onset indice de la première heure du nouveau régime
   * @param before niveau d'avant (médiane)
   * @param after niveau depuis (médiane)
   * @param spread écart robuste du régime d'avant, qui sert d'unité
   * @param statistic somme cumulée atteinte au moment du signal
   */
  public record Result(int onset, double before, double after, double spread, double statistic) {

    /** Variation, signée : positive si le niveau est monté. */
    public double magnitude() {
      return after - before;
    }

    /** Vrai si le niveau est monté. */
    public boolean rising() {
      return after > before;
    }

    /** Variation en proportion du niveau d'avant ; zéro si celui-ci était nul. */
    public double relative() {
      return before == 0 ? 0 : magnitude() / Math.abs(before);
    }
  }

  /**
   * Cherche une rupture dans une série de moyennes horaires, de la plus ancienne à la plus récente.
   *
   * <p>Le régime de référence est estimé sur le premier tiers de la série, par la médiane et
   * l'écart absolu médian : une moyenne et un écart-type ordinaires seraient tirés par la rupture
   * qu'on cherche justement à ne pas supposer connue.
   *
   * @return null si la série ne montre pas de rupture durable, ou est trop courte pour en juger
   */
  public static Result detect(double[] series, Settings settings) {
    if (series == null || settings == null) {
      throw new IllegalArgumentException("Rupture de niveau : série et réglages attendus");
    }
    int reference = Math.max(24, series.length / 3);
    if (series.length < reference + settings.minAfter()) {
      return null;
    }
    double[] head = Arrays.copyOf(series, reference);
    double centre = median(head);
    double spread =
        Math.max(
            Math.max(1.4826 * medianAbsoluteDeviation(head, centre), FLAT_FLOOR * Math.abs(centre)),
            1e-9);

    // Les deux sommes cumulées, et le dernier instant où chacune valait encore zéro : c'est de là
    // que part le nouveau régime.
    double rising = 0;
    double falling = 0;
    int lastZeroRising = reference - 1;
    int lastZeroFalling = reference - 1;
    for (int i = reference; i < series.length; i++) {
      double standardised = (series[i] - centre) / spread;
      rising = Math.max(0, rising + standardised - settings.drift());
      falling = Math.max(0, falling - standardised - settings.drift());
      if (rising == 0) {
        lastZeroRising = i;
      }
      if (falling == 0) {
        lastZeroFalling = i;
      }
      if (rising >= settings.limit() || falling >= settings.limit()) {
        boolean up = rising >= settings.limit();
        int onset = Math.min(series.length - 1, (up ? lastZeroRising : lastZeroFalling) + 1);
        return confirm(series, onset, spread, up ? rising : falling, settings);
      }
    }
    return null;
  }

  /**
   * Les trois conditions qui font d'un signal une rupture : une durée, un écart franc, et un
   * nouveau niveau qui tient encore maintenant.
   */
  private static Result confirm(
      double[] series, int onset, double spread, double statistic, Settings settings) {
    if (series.length - onset < settings.minAfter() || onset == 0) {
      return null;
    }
    double before = median(Arrays.copyOf(series, onset));
    double after = median(Arrays.copyOfRange(series, onset, series.length));
    double magnitude = Math.abs(after - before);
    double needed =
        Math.max(
            settings.minShift() * spread,
            settings.minRelative() * Math.max(Math.abs(before), Math.abs(after)));
    if (magnitude < needed) {
      // Un épisode passé : le CUSUM a bien accumulé, mais la médiane depuis la rupture est
      // retombée sur l'ancien niveau. Ce n'est pas un changement de régime.
      return null;
    }
    double[] tail = Arrays.copyOfRange(series, series.length - settings.minAfter(), series.length);
    double now = median(tail);
    if (Math.abs(now - before) < Math.abs(now - after)) {
      // Les dernières heures ressemblent plus à l'ancien niveau qu'au nouveau : c'est déjà revenu.
      return null;
    }
    return new Result(onset, before, after, spread, statistic);
  }

  static double median(double[] values) {
    double[] sorted = values.clone();
    Arrays.sort(sorted);
    int middle = sorted.length / 2;
    return sorted.length % 2 == 1 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2;
  }

  static double medianAbsoluteDeviation(double[] values, double median) {
    double[] distances = new double[values.length];
    for (int i = 0; i < values.length; i++) {
      distances[i] = Math.abs(values[i] - median);
    }
    return median(distances);
  }
}

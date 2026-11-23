package sn.ept.transdic1.alerting.service;

/**
 * Bande de tolérance par lissage exponentiel (M6, ADR 0043) : une normale qui se construit en
 * quelques dizaines de mesures, et non en six semaines.
 *
 * <p><b>Le creux que cela comble.</b> La détection d'anomalies de l'ADR 0015 compare une mesure à
 * l'habitude du même créneau — même heure, même type de jour — sur vingt-huit jours. Il lui faut
 * six moyennes horaires dans le créneau pour juger, soit plusieurs semaines d'historique <i>par
 * indicateur</i>. Pour un port de commutateur fraîchement découvert, cela veut dire des semaines
 * sans surveillance, et un commutateur a quarante-huit ports. Le lissage exponentiel, lui, a une
 * normale utilisable après une vingtaine de mesures, soit quelques minutes.
 *
 * <p><b>Le calcul.</b> Deux moyennes mobiles exponentielles : le <i>niveau</i>, et l'écart absolu à
 * ce niveau. La bande est le niveau plus ou moins {@code k} écarts. Chaque mesure met les deux à
 * jour avec un poids {@code alpha} : la normale suit donc le trafic du port, qui n'a pas de raison
 * d'être le même en novembre qu'en août.
 *
 * <p><b>Le piège, et comment il est évité.</b> Une bande exponentielle naïve s'auto-détruit : la
 * valeur aberrante qu'on veut signaler entre dans la moyenne, gonfle l'écart, et la bande s'élargit
 * assez pour accueillir la suivante. Au bout de quelques pointes, elle accepte tout. Les mesures
 * sont donc <b>ramenées au bord de la bande</b> avant d'entrer dans le lissage (winsorisation) :
 * une pointe compte comme si elle avait touché la limite, pas plus. Elle est signalée en entier,
 * mais elle ne déforme pas la normale.
 *
 * <p>Calcul pur, sans Spring ni base. L'état tient en quatre nombres, ce qui permet de le garder
 * pour des milliers de ports.
 */
public final class ExponentialBand {

  /** Poids d'une nouvelle mesure : 0,05 donne une mémoire d'environ quarante mesures. */
  public static final double DEFAULT_ALPHA = 0.05;

  /** Demi-largeur de la bande, en écarts absolus moyens. */
  public static final double DEFAULT_WIDTH = 6.0;

  /** Mesures minimales avant de juger : en dessous, la normale n'est qu'un premier reflet. */
  public static final int MIN_SAMPLES = 20;

  /** Rend l'écart absolu moyen comparable à un écart-type pour une loi normale. */
  private static final double MEAN_DEVIATION_TO_SIGMA = 1.2533;

  /**
   * Borne, en écarts, de ce qu'une seule mesure peut apprendre à la bande. Volontairement bien plus
   * serrée que la demi-largeur d'alerte, et c'est la correction d'une erreur mesurée : ramener la
   * mesure au <i>bord de la bande</i> ne suffit pas, parce que ce bord vaut déjà six écarts. Chaque
   * pointe apprenait alors un écart sept fois plus grand que l'actuel, et dix pointes de suite
   * élargissaient la bande d'un facteur huit — assez pour qu'elle finisse par avaler ce qu'elle
   * devait signaler. La bande alerte à six écarts ; elle n'apprend jamais plus de deux.
   */
  private static final double LEARN_CLAMP = 2.0;

  private ExponentialBand() {}

  /**
   * État de la bande d'un indicateur.
   *
   * @param level niveau lissé
   * @param deviation écart absolu moyen lissé
   * @param samples mesures prises en compte depuis le début
   */
  public record State(double level, double deviation, int samples) {

    /**
     * Vrai quand la bande a vu assez de mesures pour qu'un dépassement veuille dire quelque chose.
     */
    public boolean ready() {
      return samples >= MIN_SAMPLES;
    }

    /** Écart robuste de la bande, avec un plancher pour ne pas crier sur une série plate. */
    public double spread(double width) {
      double floor = Math.max(0.5, Math.abs(level) * 0.02);
      return width * Math.max(deviation * MEAN_DEVIATION_TO_SIGMA, floor);
    }

    /** Borne basse de la bande. */
    public double low(double width) {
      return level - spread(width);
    }

    /** Borne haute de la bande. */
    public double high(double width) {
      return level + spread(width);
    }

    /** Écart d'une mesure à la bande, en nombre de demi-largeurs ; 0 à l'intérieur. */
    public double excess(double value, double width) {
      double spread = spread(width);
      if (spread <= 0) {
        return 0;
      }
      double distance = Math.abs(value - level) - spread;
      // Une mesure pile sur le bord n'est pas dehors : sans cette marge, l'arrondi binaire la
      // ferait sortir d'un cheveu de 1e-16.
      return distance <= spread * 1e-9 ? 0 : distance / spread;
    }

    /** Vrai si la mesure sort de la bande. */
    public boolean outside(double value, double width) {
      return ready() && excess(value, width) > 0;
    }
  }

  /** Première mesure : le niveau est la valeur, et l'écart part de zéro. */
  public static State start(double value) {
    return new State(value, 0, 1);
  }

  /**
   * Intègre une mesure.
   *
   * <p>La valeur est d'abord ramenée à {@link #LEARN_CLAMP} écarts du niveau : sans cela, la pointe
   * qu'on veut signaler élargirait la bande assez pour accueillir la suivante, et la détection
   * s'éteindrait d'elle-même, en silence.
   */
  public static State update(State state, double value, double alpha, double width) {
    if (state == null) {
      return start(value);
    }
    double weight = Math.min(0.5, Math.max(0.001, alpha));
    double bounded = winsorise(state, value, Math.min(width, LEARN_CLAMP));
    double level = state.level() + weight * (bounded - state.level());
    double deviation =
        state.deviation() + weight * (Math.abs(bounded - state.level()) - state.deviation());
    return new State(
        level, Math.max(0, deviation), Math.min(Integer.MAX_VALUE - 1, state.samples() + 1));
  }

  /**
   * Mesure ramenée à {@code width} écarts du niveau. Tant que la bande n'est pas prête, rien n'est
   * ramené : il faut bien que la normale se construise sur les vraies valeurs.
   */
  static double winsorise(State state, double value, double width) {
    if (!state.ready()) {
      return value;
    }
    double low = state.low(width);
    double high = state.high(width);
    return Math.min(high, Math.max(low, value));
  }
}

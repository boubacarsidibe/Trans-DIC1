package sn.ept.transdic1.collection.service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;

/**
 * Débit d'une interface réseau à partir de deux relevés de compteur. Les compteurs d'IF-MIB sont
 * cumulés depuis le démarrage de l'équipement : seule la différence entre deux passages a un sens.
 *
 * <p>Trois pièges, tous traités ici : le compteur revient à zéro quand il dépasse sa capacité (2^32
 * ou 2^64), il repart de zéro quand l'équipement redémarre, et un écart de temps trop court ou trop
 * long ne donne rien de fiable.
 */
public final class InterfaceRate {
  /** Capacité d'un compteur 32 bits : au-delà, la valeur lue vient d'un compteur 64 bits. */
  static final BigInteger WIDTH_32 = BigInteger.ONE.shiftLeft(32);

  /** En dessous, l'écart de temps est trop court pour un débit sensé. */
  static final Duration MIN_GAP = Duration.ofSeconds(10);

  /** Au-delà, le trou est trop grand : le débit moyen n'apprend plus rien. */
  static final Duration MAX_GAP = Duration.ofHours(2);

  private InterfaceRate() {}

  /**
   * Valeur par seconde entre deux relevés, ou null si elle n'est pas calculable (premier passage,
   * écart de temps hors bornes, ou saut impossible à interpréter).
   *
   * @param previous compteur lu au passage précédent
   * @param current compteur lu maintenant
   */
  public static BigDecimal perSecond(
      BigInteger previous, Instant previousAt, BigInteger current, Instant now) {
    if (previous == null || previousAt == null || current == null || now == null) {
      return null;
    }
    Duration gap = Duration.between(previousAt, now);
    if (gap.compareTo(MIN_GAP) < 0 || gap.compareTo(MAX_GAP) > 0) {
      return null;
    }
    BigInteger delta = delta(previous, current);
    if (delta == null) {
      return null;
    }
    return new BigDecimal(delta)
        .divide(BigDecimal.valueOf(gap.toMillis()).movePointLeft(3), 4, RoundingMode.HALF_UP);
  }

  /**
   * Écart entre deux relevés. Un compteur qui recule a soit débordé, soit été remis à zéro par un
   * redémarrage, et les deux ne se distinguent pas dans la valeur elle-même. On tranche par la
   * taille du compteur :
   *
   * <ul>
   *   <li><b>32 bits</b> (moins de 2^32) : le débordement est banal, une minute suffit sur un lien
   *       à 1 Gb/s. On le rattrape.
   *   <li><b>64 bits</b> : il faudrait des dizaines d'années à 100 Gb/s pour déborder. Un recul est
   *       donc un redémarrage : aucune valeur, plutôt qu'un pic inventé.
   * </ul>
   *
   * <p>Limite connue : le redémarrage d'un équipement qui ne sert que des compteurs 32 bits produit
   * une valeur trop haute une fois. Les équipements modernes servent les compteurs 64 bits
   * d'IF-MIB, que la collecte lit en premier.
   */
  static BigInteger delta(BigInteger previous, BigInteger current) {
    if (current.compareTo(previous) >= 0) {
      return current.subtract(previous);
    }
    if (previous.compareTo(WIDTH_32) < 0) {
      return WIDTH_32.subtract(previous).add(current);
    }
    return null;
  }

  /**
   * Part de la capacité du lien utilisée, en pourcentage, ou null si la vitesse est inconnue. Le
   * compteur est en octets, la vitesse annoncée en bits par seconde.
   */
  public static BigDecimal usagePercent(BigDecimal octetsPerSecond, Long speedBps) {
    if (octetsPerSecond == null || speedBps == null || speedBps <= 0) {
      return null;
    }
    BigDecimal bits = octetsPerSecond.multiply(BigDecimal.valueOf(8));
    return bits.multiply(BigDecimal.valueOf(100))
        .divide(BigDecimal.valueOf(speedBps), 2, RoundingMode.HALF_UP)
        .min(BigDecimal.valueOf(100));
  }
}

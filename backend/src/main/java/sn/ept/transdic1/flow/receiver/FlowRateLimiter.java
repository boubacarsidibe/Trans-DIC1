package sn.ept.transdic1.flow.receiver;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Plafond de paquets par seconde et par exportateur.
 *
 * <p>Un équipement qui s'emballe — ou qu'on fait s'emballer — ne doit pas occuper le thread du
 * socket au détriment des autres. Le compte est remis à zéro à chaque seconde entamée : c'est
 * grossier, mais c'est sans verrou et cela suffit pour écrêter.
 *
 * <p>Le nombre de sources suivies est borné : la table est vidée quand elle dépasse, ce qui ne peut
 * arriver qu'avec des adresses usurpées, lesquelles sont de toute façon rejetées avant.
 */
public class FlowRateLimiter {
  static final int MAX_SOURCES = 5_000;

  private record Window(long second, int count) {}

  private final Map<String, Window> windows = new ConcurrentHashMap<>();
  private final Clock clock;
  private final int perSecond;

  public FlowRateLimiter(Clock clock, int perSecond) {
    this.clock = clock;
    this.perSecond = perSecond;
  }

  /** Vrai si ce paquet peut être traité. */
  public boolean accept(String source) {
    long second = clock.instant().getEpochSecond();
    if (windows.size() > MAX_SOURCES) {
      windows.clear();
    }
    Window updated =
        windows.compute(
            source,
            (key, current) ->
                current == null || current.second() != second
                    ? new Window(second, 1)
                    : new Window(second, current.count() + 1));
    return updated.count() <= perSecond;
  }
}

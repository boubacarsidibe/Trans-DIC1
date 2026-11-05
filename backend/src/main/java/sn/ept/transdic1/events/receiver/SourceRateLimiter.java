package sn.ept.transdic1.events.receiver;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongSupplier;

/**
 * Limite le nombre d'événements acceptés par adresse source et par seconde (fenêtre fixe d'une
 * seconde). Le nombre d'adresses suivies est borné : des sources usurpées en masse ne peuvent pas
 * épuiser la mémoire ; au-delà, la table est vidée (les compteurs repartent de zéro).
 */
public class SourceRateLimiter {
  public static final int MAX_SOURCES = 10_000;

  private final int perSecond;
  private final LongSupplier clockMillis;
  private final ConcurrentMap<String, Window> windows = new ConcurrentHashMap<>();

  private static final class Window {
    long second;
    int count;
  }

  public SourceRateLimiter(int perSecond, LongSupplier clockMillis) {
    this.perSecond = Math.max(1, perSecond);
    this.clockMillis = clockMillis;
  }

  /** Vrai si l'événement de cette source est accepté dans la seconde courante. */
  public boolean tryAcquire(String source) {
    long second = clockMillis.getAsLong() / 1000;
    if (windows.size() >= MAX_SOURCES && !windows.containsKey(source)) {
      windows.clear();
    }
    Window window = windows.computeIfAbsent(source, s -> new Window());
    synchronized (window) {
      if (window.second != second) {
        window.second = second;
        window.count = 0;
      }
      if (window.count >= perSecond) {
        return false;
      }
      window.count++;
      return true;
    }
  }

  int trackedSources() {
    return windows.size();
  }
}

package sn.ept.transdic1.events.receiver;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongSupplier;

/**
 * Limite le nombre d'événements acceptés par adresse source et par seconde (fenêtre fixe d'une
 * seconde). Seules les sources connues de l'inventaire arrivent ici (le contrôle de la source est
 * fait avant), donc la table est bornée par la taille de l'inventaire ; elle reste plafonnée à
 * {@value #MAX_SOURCES} adresses par sécurité : table pleine, on évince la source restée inactive
 * le plus longtemps, jamais une source bavarde de la seconde courante.
 */
public class SourceRateLimiter {
  public static final int MAX_SOURCES = 10_000;

  private final int perSecond;
  private final LongSupplier clockMillis;
  private final ConcurrentMap<String, Window> windows = new ConcurrentHashMap<>();

  private static final class Window {
    volatile long second;
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
      evictLeastRecent();
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

  /** Retire l'adresse dont la dernière fenêtre est la plus ancienne (rare : table pleine). */
  private synchronized void evictLeastRecent() {
    if (windows.size() < MAX_SOURCES) {
      return;
    }
    String oldest = null;
    long oldestSecond = Long.MAX_VALUE;
    for (Map.Entry<String, Window> entry : windows.entrySet()) {
      long s = entry.getValue().second;
      if (s < oldestSecond) {
        oldestSecond = s;
        oldest = entry.getKey();
      }
    }
    if (oldest != null) {
      windows.remove(oldest);
    }
  }

  int trackedSources() {
    return windows.size();
  }
}

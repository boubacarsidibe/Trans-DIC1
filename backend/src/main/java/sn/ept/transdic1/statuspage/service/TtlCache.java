package sn.ept.transdic1.statuspage.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Cache à durée de vie fixe, borné en nombre d'entrées : une seule valeur calculée par clé et par
 * période, même sous une rafale de lectures concurrentes (les lecteurs d'une clé expirée attendent
 * le calcul en cours au lieu de le relancer). Sert aux pages de statut : un afflux de visites ne
 * déclenche qu'un calcul toutes les {@code ttl}.
 */
final class TtlCache<K, V> {
  private record Entry<V>(V value, Instant expiresAt) {}

  private final Map<K, Entry<V>> entries = new ConcurrentHashMap<>();
  private final Map<K, Object> locks = new ConcurrentHashMap<>();
  private final Clock clock;
  private final Duration ttl;
  private final int maxEntries;

  TtlCache(Clock clock, Duration ttl, int maxEntries) {
    this.clock = clock;
    this.ttl = ttl;
    this.maxEntries = maxEntries;
  }

  V get(K key, Supplier<V> loader) {
    Entry<V> entry = entries.get(key);
    if (fresh(entry)) {
      return entry.value();
    }
    synchronized (locks.computeIfAbsent(key, k -> new Object())) {
      entry = entries.get(key);
      if (fresh(entry)) {
        return entry.value();
      }
      V value = loader.get();
      if (entries.size() >= maxEntries && !entries.containsKey(key)) {
        // Borne mémoire : on repart de zéro plutôt que de grossir sans fin.
        entries.clear();
      }
      entries.put(key, new Entry<>(value, clock.instant().plus(ttl)));
      return value;
    }
  }

  void invalidate(K key) {
    entries.remove(key);
  }

  void clear() {
    entries.clear();
  }

  private boolean fresh(Entry<V> entry) {
    return entry != null && clock.instant().isBefore(entry.expiresAt());
  }
}

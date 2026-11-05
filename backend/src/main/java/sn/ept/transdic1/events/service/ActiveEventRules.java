package sn.ept.transdic1.events.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import sn.ept.transdic1.events.domain.EventRule;
import sn.ept.transdic1.events.repository.EventRuleRepository;

/**
 * Cache des règles d'événements actives (ADR 0021) : une requête (portée comprise, par jointure)
 * toutes les {@code events.rule-cache-seconds} secondes au plus, au lieu de 1 + 2N requêtes par
 * événement. Vidé à chaque création, modification ou suppression de règle (et de nouveau après la
 * validation de la transaction, pour ne jamais garder l'état d'avant). 0 désactive le cache. Les
 * règles rendues sont détachées et ne doivent pas être modifiées.
 */
@Component
public class ActiveEventRules {
  private record Snapshot(List<EventRule> rules, Instant loadedAt) {}

  private final EventRuleRepository rules;
  private final Clock clock;
  private final Duration ttl;
  private volatile Snapshot snapshot;

  public ActiveEventRules(
      EventRuleRepository rules,
      Clock clock,
      @Value("${events.rule-cache-seconds:10}") long cacheSeconds) {
    this.rules = rules;
    this.clock = clock;
    this.ttl = Duration.ofSeconds(Math.max(0, cacheSeconds));
  }

  public List<EventRule> get() {
    Snapshot s = snapshot;
    Instant now = clock.instant();
    if (s == null || ttl.isZero() || Duration.between(s.loadedAt(), now).compareTo(ttl) >= 0) {
      s = new Snapshot(List.copyOf(rules.findActiveWithScope()), now);
      if (!ttl.isZero()) {
        snapshot = s;
      }
    }
    return s.rules();
  }

  /** Vide le cache tout de suite et, dans une transaction, encore après sa validation. */
  public void evict() {
    snapshot = null;
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
              snapshot = null;
            }
          });
    }
  }
}

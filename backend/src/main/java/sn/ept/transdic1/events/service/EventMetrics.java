package sn.ept.transdic1.events.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;
import sn.ept.transdic1.events.domain.EventSource;

/**
 * Métriques des récepteurs d'événements (ADR 0021) : événements reçus, stockés, rejetés par raison
 * ({@code unknown_source}, {@code rate_limited}, {@code queue_full}, {@code bad_community}, {@code
 * bad_user}, {@code stopped}), datagrammes tronqués et expressions interrompues.
 */
@Component
public class EventMetrics {
  private final MeterRegistry registry;
  private final ConcurrentMap<String, Counter> dropped = new ConcurrentHashMap<>();
  private final Counter truncated;
  private final Counter regexAborted;

  public EventMetrics(MeterRegistry registry) {
    this.registry = registry;
    truncated =
        Counter.builder("events.truncated")
            .description("Datagrammes syslog tronqués à 8 Kio")
            .register(registry);
    regexAborted =
        Counter.builder("events.regex.aborted")
            .description("Expressions de règle interrompues (budget de calcul dépassé)")
            .register(registry);
  }

  public void received(EventSource source) {
    Counter.builder("events.received")
        .description("Événements reçus par les récepteurs")
        .tag("source", source.name())
        .register(registry)
        .increment();
  }

  public void stored(EventSource source) {
    Counter.builder("events.stored")
        .description("Événements enregistrés (source connue)")
        .tag("source", source.name())
        .register(registry)
        .increment();
  }

  public void dropped(String reason) {
    dropped
        .computeIfAbsent(
            reason,
            r ->
                Counter.builder("events.dropped")
                    .description("Événements rejetés, par raison")
                    .tag("reason", r)
                    .register(registry))
        .increment();
  }

  public void truncated() {
    truncated.increment();
  }

  public void regexAborted() {
    regexAborted.increment();
  }
}

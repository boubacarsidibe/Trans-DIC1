package sn.ept.transdic1.monitoring.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import sn.ept.transdic1.monitoring.domain.MetricIngestedEvent;

/**
 * Métriques métier de l'ingestion : mesures reçues et mesures retenues (qualité non mauvaise).
 * Comptées une fois la transaction validée : une ingestion annulée n'est pas comptée.
 */
@Service
public class MeasureMetricsService {
  private final Counter received;
  private final Counter accepted;

  public MeasureMetricsService(MeterRegistry registry) {
    this.received =
        Counter.builder("measures.received")
            .description("Mesures reçues (agents, SNMP, source simulée)")
            .register(registry);
    this.accepted =
        Counter.builder("measures.accepted")
            .description("Mesures enregistrées avec une qualité exploitable")
            .register(registry);
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  public void onIngested(MetricIngestedEvent event) {
    received.increment(event.received());
    accepted.increment(event.accepted());
  }
}

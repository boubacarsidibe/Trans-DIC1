package sn.ept.transdic1.events.receiver;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import sn.ept.transdic1.events.service.EventMetrics;
import sn.ept.transdic1.events.service.NetworkEventIngestionService;

/**
 * File et travailleurs communs aux récepteurs (ADR 0021). Les travailleurs ne démarrent que si un
 * récepteur est activé ; ils s'arrêtent à la fermeture du contexte.
 */
@Configuration
public class EventReceiverConfiguration {

  @Bean(destroyMethod = "close")
  EventIntake eventIntake(
      NetworkEventIngestionService ingestion,
      EventMetrics metrics,
      @Value("${events.queue-capacity:10000}") int capacity,
      @Value("${events.workers:2}") int workers,
      @Value("${events.rate-limit-per-source:100}") int ratePerSource) {
    return new EventIntake(
        capacity,
        workers,
        new SourceRateLimiter(ratePerSource, System::currentTimeMillis),
        ingestion::ingest,
        metrics);
  }
}

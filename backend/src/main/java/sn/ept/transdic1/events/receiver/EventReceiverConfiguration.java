package sn.ept.transdic1.events.receiver;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import sn.ept.transdic1.events.service.EventMetrics;
import sn.ept.transdic1.events.service.EventSourceDirectory;
import sn.ept.transdic1.events.service.NetworkEventIngestionService;

/**
 * Files et travailleurs communs aux récepteurs (ADR 0021). Les travailleurs ne démarrent que si un
 * récepteur est activé ; ils s'arrêtent à la fermeture du contexte.
 */
@Configuration
public class EventReceiverConfiguration {

  @Bean(destroyMethod = "close")
  EventIntake eventIntake(
      NetworkEventIngestionService ingestion,
      EventSourceDirectory directory,
      EventMetrics metrics,
      @Value("${events.queue-capacity:10000}") int capacity,
      @Value("${events.workers:2}") int workers,
      @Value("${events.rate-limit-per-source:100}") int ratePerSource) {
    return new EventIntake(
        capacity,
        workers,
        directory::isKnown,
        new SourceRateLimiter(ratePerSource, System::currentTimeMillis),
        event -> ingestOnceMore(ingestion, directory, event),
        metrics);
  }

  /**
   * Traite l'événement dans sa propre transaction. Une violation de contrainte (alerte déjà active
   * ouverte par une autre instance, équipement supprimé depuis le dernier chargement du cache)
   * annule toute la transaction PostgreSQL : on relit l'inventaire et on rejoue une fois, l'alerte
   * active est alors trouvée et les événements sont stockés.
   */
  static void ingestOnceMore(
      NetworkEventIngestionService ingestion, EventSourceDirectory directory, ReceivedEvent event) {
    try {
      ingestion.ingest(event);
    } catch (DataIntegrityViolationException e) {
      directory.invalidate();
      ingestion.ingest(event);
    }
  }
}

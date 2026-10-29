package sn.ept.transdic1.collection.service;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import sn.ept.transdic1.collection.port.MetricSource;
import sn.ept.transdic1.collection.port.MetricSourceException;
import sn.ept.transdic1.common.scheduling.JobGuard;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;

/**
 * Cycle planifié de la collecte simulée, pour le développement et les démonstrations. Désactivé par
 * défaut : {@code collection.simulated.enabled=true} l'active. Un tour ne démarre jamais tant que
 * le précédent n'est pas fini (le planificateur compte l'intervalle après la fin du tour, et un
 * verrou local protège les appels directs). Chaque équipement est lu avec un nombre borné de
 * tentatives ; un équipement en échec n'empêche pas les autres.
 */
@Service
@ConditionalOnProperty(name = "collection.simulated.enabled", havingValue = "true")
public class SimulatedCollectionService {
  private static final Logger LOG = LoggerFactory.getLogger(SimulatedCollectionService.class);

  private final EquipmentRepository equipments;
  private final MeasureIngestionService ingestion;
  private final MetricSource source;
  private final MeterRegistry meters;
  private final Clock clock;
  private final int maxAttempts;
  private final JobGuard guard;
  private final AtomicBoolean running = new AtomicBoolean(false);

  public SimulatedCollectionService(
      EquipmentRepository equipments,
      MeasureIngestionService ingestion,
      SimulatedMetricSource source,
      MeterRegistry meters,
      Clock clock,
      JobGuard guard,
      @Value("${collection.simulated.max-attempts:3}") int maxAttempts) {
    this.equipments = equipments;
    this.ingestion = ingestion;
    this.source = source;
    this.meters = meters;
    this.clock = clock;
    this.guard = guard;
    this.maxAttempts = Math.max(1, maxAttempts);
  }

  /** Tour planifié : une seule instance collecte à la fois. */
  @Scheduled(
      fixedDelayString = "${collection.simulated.interval-ms:30000}",
      initialDelayString = "${collection.simulated.initial-delay-ms:5000}")
  public void scheduledRound() {
    guard.run("collection.simulated", Duration.ofMinutes(5), this::collectOnce);
  }

  /**
   * Un tour de collecte ; retourne le nombre d'équipements lus, ou -1 si un tour était en cours.
   */
  public int collectOnce() {
    if (!running.compareAndSet(false, true)) {
      LOG.warn("Collecte simulée ignorée : le tour précédent n'est pas terminé");
      meters.counter("collection.simulated.skipped").increment();
      return -1;
    }
    try {
      Instant now = clock.instant();
      List<Equipment> targets = equipments.findByMonitoringEnabledTrue();
      int read = 0;
      for (Equipment equipment : targets) {
        if (collect(equipment, now)) {
          read++;
        }
      }
      LOG.info("Collecte simulée : {} équipement(s) lu(s) sur {}", read, targets.size());
      return read;
    } finally {
      running.set(false);
    }
  }

  private boolean collect(Equipment equipment, Instant now) {
    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
      try {
        ingestion.ingest(equipment.getId(), source.read(equipment, now));
        meters.counter("collection.simulated.reads", "result", "success").increment();
        return true;
      } catch (MetricSourceException | RuntimeException e) {
        // Ni valeur ni adresse dans les journaux : seulement l'identifiant et la nature de l'échec.
        LOG.warn(
            "Lecture simulée en échec pour {} (tentative {}/{}) : {}",
            equipment.getId(),
            attempt,
            maxAttempts,
            e.getClass().getSimpleName());
      }
    }
    meters.counter("collection.simulated.reads", "result", "failure").increment();
    return false;
  }
}

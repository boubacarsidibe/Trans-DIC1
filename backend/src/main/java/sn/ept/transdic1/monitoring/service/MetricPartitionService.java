package sn.ept.transdic1.monitoring.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Prépare les partitions mensuelles de la table metric (ADR-0004). Une mesure hors partition
 * tomberait dans la partition par défaut ; créer les mois à venir à l'avance évite cela.
 *
 * <p>La suppression des partitions expirées (rétention de 30 jours) n'est pas faite ici : elle
 * attend les agrégats horaires, sans quoi l'historique serait perdu.
 */
@Service
public class MetricPartitionService {
  private static final Logger LOG = LoggerFactory.getLogger(MetricPartitionService.class);

  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final int monthsAhead;

  public MetricPartitionService(
      JdbcTemplate jdbc,
      Clock clock,
      @Value("${monitoring.metric.partitions-ahead-months:3}") int monthsAhead) {
    this.jdbc = jdbc;
    this.clock = clock;
    this.monthsAhead = monthsAhead;
  }

  /** Crée, si elles manquent, les partitions du mois courant et des mois suivants. */
  @EventListener(ApplicationReadyEvent.class)
  @Scheduled(cron = "${monitoring.metric.partition-cron:0 15 3 * * *}", zone = "UTC")
  public List<String> ensureUpcomingPartitions() {
    LocalDate month = LocalDate.now(clock.withZone(ZoneOffset.UTC)).withDayOfMonth(1);
    List<String> partitions = new ArrayList<>();
    for (int i = 0; i <= monthsAhead; i++) {
      LocalDate start = month.plusMonths(i);
      try {
        partitions.add(
            jdbc.queryForObject(
                "SELECT ensure_metric_partition(?::date)", String.class, start.toString()));
      } catch (RuntimeException e) {
        // Une exécution concurrente a pu créer la partition : on réessaiera au prochain passage.
        LOG.warn("Partition de metric non créée pour {} : {}", start, e.getMessage());
      }
    }
    return partitions;
  }
}

package sn.ept.transdic1.flow.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.common.scheduling.JobGuard;
import sn.ept.transdic1.common.threat.NetworkScanDetected;

/**
 * Écriture des flux comptés et purge des anciens (ADR 0032).
 *
 * <p>Une fois par minute, ce qui a été compté en mémoire ({@link FlowAccumulator}) passe en base :
 * les totaux par équipement, toujours justes, et les {@code flow.top-conversations} plus grosses
 * conversations de la minute. Le reste est abandonné : personne ne lit la trois-centième
 * conversation d'une minute, et la garder coûterait cher pour rien.
 *
 * <p>L'instant retenu est celui de la <b>réception</b>, arrondi à la minute, et non celui que porte
 * le flux. Les horodatages NetFlow sont relatifs au temps de fonctionnement de l'exportateur : les
 * convertir demanderait de connaître l'instant de son démarrage, pour un gain nul sur des agrégats
 * d'une minute.
 *
 * <p>Les adresses sont des données personnelles : elles sont purgées au bout de {@code
 * flow.retention-days} jours, par lots, comme les autres journaux.
 */
@Service
public class FlowCollectionService {
  private static final Logger LOG = LoggerFactory.getLogger(FlowCollectionService.class);
  static final int PURGE_BATCH = 20_000;

  private final FlowAccumulator accumulator;
  private final FlowObservations observations;
  private final JdbcTemplate jdbc;
  private final JobGuard guard;
  private final ApplicationEventPublisher publisher;
  private final Clock clock;
  private final int topConversations;
  private final Duration retention;
  private final boolean scanDetection;
  private final ScanDetector.Thresholds thresholds;

  public FlowCollectionService(
      FlowAccumulator accumulator,
      FlowObservations observations,
      JdbcTemplate jdbc,
      JobGuard guard,
      ApplicationEventPublisher publisher,
      Clock clock,
      @Value("${flow.top-conversations:300}") int topConversations,
      @Value("${flow.retention-days:7}") long retentionDays,
      @Value("${flow.scan-detection.enabled:true}") boolean scanDetection,
      @Value("${flow.scan-targets:200}") int scanTargets,
      @Value("${flow.scan-ports:100}") int scanPorts,
      @Value("${flow.flood-sources:200}") int floodSources,
      @Value("${flow.scan-entropy:0.9}") double minimumEntropy,
      @Value("${flow.scan-max-bytes-per-flow:1000}") long maximumBytesPerFlow) {
    this.accumulator = accumulator;
    this.observations = observations;
    this.jdbc = jdbc;
    this.guard = guard;
    this.publisher = publisher;
    this.clock = clock;
    this.topConversations = Math.max(10, topConversations);
    this.retention = Duration.ofDays(Math.max(1, retentionDays));
    this.scanDetection = scanDetection;
    this.thresholds =
        new ScanDetector.Thresholds(
            Math.max(20, scanTargets),
            Math.max(10, scanPorts),
            Math.max(20, floodSources),
            Math.min(1, Math.max(0.5, minimumEntropy)),
            Math.max(100, maximumBytesPerFlow));
  }

  @Scheduled(
      fixedDelayString = "${flow.flush-ms:60000}",
      initialDelayString = "${flow.flush-ms:60000}")
  public void scheduledFlush() {
    try {
      flush();
    } catch (RuntimeException e) {
      // Un échec d'écriture ne doit pas arrêter la tâche planifiée : la minute suivante réessaie.
      LOG.warn("Écriture des flux réseau impossible : {}", e.getClass().getSimpleName());
    }
  }

  /**
   * Vide les compteurs en base.
   *
   * @return le nombre de conversations écrites
   */
  @Transactional
  public int flush() {
    detectScans();
    FlowAccumulator.Snapshot snapshot = accumulator.drain();
    if (snapshot.totals().isEmpty()) {
      return 0;
    }
    Instant bucket = clock.instant().truncatedTo(ChronoUnit.MINUTES);
    writeTotals(bucket, snapshot.totals());
    int written = writeConversations(bucket, snapshot);
    LOG.debug(
        "Flux réseau écrits : {} équipement(s), {} conversation(s)",
        snapshot.totals().size(),
        written);
    return written;
  }

  private void writeTotals(Instant bucket, Map<UUID, FlowAccumulator.EquipmentTotals> totals) {
    var rows = new ArrayList<Object[]>(totals.size());
    totals.forEach(
        (equipmentId, counters) ->
            rows.add(
                new Object[] {
                  equipmentId,
                  Timestamp.from(bucket),
                  counters.bytes(),
                  counters.packets(),
                  counters.flows(),
                  counters.conversations(),
                  counters.truncated()
                }));
    jdbc.batchUpdate(
        "INSERT INTO flow_bucket (equipment_id, bucket_start, bytes, packets, flows,"
            + " conversations, truncated) VALUES (?, ?, ?, ?, ?, ?, ?)"
            // Deux vidages dans la même minute : on additionne plutôt que d'échouer.
            + " ON CONFLICT (equipment_id, bucket_start) DO UPDATE SET"
            + " bytes = flow_bucket.bytes + EXCLUDED.bytes,"
            + " packets = flow_bucket.packets + EXCLUDED.packets,"
            + " flows = flow_bucket.flows + EXCLUDED.flows,"
            + " conversations = flow_bucket.conversations + EXCLUDED.conversations,"
            + " truncated = flow_bucket.truncated OR EXCLUDED.truncated",
        rows);
  }

  private int writeConversations(Instant bucket, FlowAccumulator.Snapshot snapshot) {
    var rows = new ArrayList<Object[]>();
    for (UUID equipmentId : snapshot.totals().keySet()) {
      for (var entry :
          FlowAccumulator.top(snapshot.conversations(), equipmentId, topConversations)) {
        FlowAccumulator.Conversation key = entry.getKey();
        rows.add(
            new Object[] {
              equipmentId,
              Timestamp.from(bucket),
              key.sourceAddress(),
              key.destinationAddress(),
              (short) key.protocol(),
              key.destinationPort(),
              entry.getValue().bytes(),
              entry.getValue().packets(),
              entry.getValue().flows()
            });
      }
    }
    if (rows.isEmpty()) {
      return 0;
    }
    jdbc.batchUpdate(
        "INSERT INTO flow_aggregate (equipment_id, bucket_start, source_address,"
            + " destination_address, protocol, destination_port, bytes, packets, flows)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
            + " ON CONFLICT (equipment_id, bucket_start, source_address, destination_address,"
            + " protocol, destination_port) DO UPDATE SET"
            + " bytes = flow_aggregate.bytes + EXCLUDED.bytes,"
            + " packets = flow_aggregate.packets + EXCLUDED.packets,"
            + " flows = flow_aggregate.flows + EXCLUDED.flows",
        rows);
    return rows.size();
  }

  /**
   * Examine la minute écoulée à la recherche de balayages et d'inondations (S6), et publie ce
   * qu'elle trouve. C'est {@code threat} qui ouvre les alertes : {@code flow} observe, il ne juge
   * pas de la gravité.
   *
   * @return le nombre de faits signalés
   */
  public int detectScans() {
    var views = observations.drain();
    if (!scanDetection || views.isEmpty()) {
      return 0;
    }
    int found = 0;
    for (var entry : views.entrySet()) {
      for (NetworkScanDetected finding :
          ScanDetector.examine(
              entry.getKey(),
              entry.getValue().sources(),
              entry.getValue().destinations(),
              thresholds)) {
        publisher.publishEvent(finding);
        found++;
      }
    }
    if (found > 0) {
      LOG.info("Balayages ou inondations repérés dans les flux : {}", found);
    }
    return found;
  }

  @Scheduled(cron = "${flow.retention-cron:0 45 4 * * *}", zone = "UTC")
  public void scheduledPurge() {
    guard.run("flow.retention", Duration.ofMinutes(10), this::purgeExpired);
  }

  /** Supprime les flux expirés ; retourne le nombre de lignes supprimées. */
  public int purgeExpired() {
    Instant before = clock.instant().minus(retention);
    int total = purge("flow_aggregate", before) + purge("flow_bucket", before);
    if (total > 0) {
      LOG.info("Flux réseau purgés : {} ligne(s)", total);
    }
    return total;
  }

  private int purge(String table, Instant before) {
    int total = 0;
    int deleted;
    do {
      deleted =
          jdbc.update(
              "DELETE FROM "
                  + table
                  + " WHERE ctid IN (SELECT ctid FROM "
                  + table
                  + " WHERE bucket_start < ? LIMIT ?)",
              Timestamp.from(before),
              PURGE_BATCH);
      total += deleted;
    } while (deleted == PURGE_BATCH);
    return total;
  }

  /** Conversations comptées en mémoire en ce moment. */
  public int pending() {
    return accumulator.size();
  }
}

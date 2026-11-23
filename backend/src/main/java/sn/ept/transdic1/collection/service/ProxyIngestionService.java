package sn.ept.transdic1.collection.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import sn.ept.transdic1.collection.dto.MeasureInput;
import sn.ept.transdic1.collection.dto.ProxyIngestionResult;
import sn.ept.transdic1.collection.dto.ProxyMeasuresRequest;
import sn.ept.transdic1.inventory.domain.ProxyBatch;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.ProxyBatchRepository;

/**
 * Reçoit les lots d'un proxy de collecte (ADR 0047) : vérifie qu'ils concernent bien son site,
 * écarte ce qui est hors de la fenêtre de rattrapage, reconnaît un rejeu, et confie le reste à
 * {@link MeasureIngestionService}.
 *
 * <p>Trois décisions valent d'être lues ici, parce qu'elles vont contre le réflexe de tout refuser
 * au premier défaut.
 *
 * <ul>
 *   <li><b>Un lot n'est jamais rejeté en entier pour une mesure fautive.</b> Un proxy qui reçoit
 *       400 renvoie le même lot, et son tampon ne se vide plus jamais : une seule mesure trop
 *       vieille ou mal adressée gèlerait la collecte de tout un site. Les mesures fautives sont
 *       comptées dans le bilan, et le proxy sait quoi oublier.
 *   <li><b>Un équipement d'un autre site est refusé, pas accepté « au cas où ».</b> Le jeton d'un
 *       proxy vaut pour son site et rien d'autre : volé dans un local technique, il ne doit pas
 *       permettre d'écrire des mesures sur tout le campus.
 *   <li><b>Un rejeu renvoie le premier bilan.</b> Sans cela, un accusé de réception perdu suffirait
 *       à stocker deux fois les mêmes mesures, et les moyennes horaires seraient fausses.
 * </ul>
 */
@Service
public class ProxyIngestionService {
  private static final Logger LOG = LoggerFactory.getLogger(ProxyIngestionService.class);

  /** Au-delà, le lot est refusé : c'est une erreur du proxy, pas un tampon qui se vide. */
  static final int MAX_MEASURES = 5_000;

  private final EquipmentRepository equipments;
  private final ProxyBatchRepository batches;
  private final MeasureIngestionService ingestion;
  private final Clock clock;
  private final Duration backfill;
  private final Duration freshness;

  public ProxyIngestionService(
      EquipmentRepository equipments,
      ProxyBatchRepository batches,
      MeasureIngestionService ingestion,
      Clock clock,
      @Value("${collection.proxy.backfill-hours:24}") long backfillHours,
      @Value("${collection.proxy.freshness-seconds:600}") long freshnessSeconds) {
    this.equipments = equipments;
    this.batches = batches;
    this.ingestion = ingestion;
    this.clock = clock;
    this.backfill = Duration.ofHours(backfillHours);
    this.freshness = Duration.ofSeconds(freshnessSeconds);
  }

  @Transactional
  public ProxyIngestionResult ingest(UUID proxyId, UUID siteId, ProxyMeasuresRequest request) {
    int total = request.equipements().stream().mapToInt(e -> e.mesures().size()).sum();
    if (total == 0 || total > MAX_MEASURES) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Entre 1 et " + MAX_MEASURES + " mesures par lot");
    }

    var known = batches.findByProxyIdAndLotId(proxyId, request.lotId());
    if (known.isPresent()) {
      var first = known.get();
      return new ProxyIngestionResult(
          first.getStored(),
          first.getEvaluated(),
          0,
          0,
          first.getAlertsOpened(),
          first.getAlertsClosed(),
          true);
    }

    Instant now = clock.instant();
    Instant oldest = now.minus(backfill);
    Instant evaluateFrom = now.minus(freshness);
    var tally = new Tally();

    for (var group : request.equipements()) {
      var equipment = equipments.findById(group.equipementId()).orElse(null);
      if (equipment == null
          || equipment.getSite() == null
          || !siteId.equals(equipment.getSite().getId())) {
        // Hors site ou inconnu : on compte et on passe, sans détailler lequel au proxy.
        tally.outOfSite += group.mesures().size();
        LOG.warn(
            "Proxy {} : {} mesures écartées pour l'équipement {}, hors de son site",
            proxyId,
            group.mesures().size(),
            group.equipementId());
        continue;
      }
      if (!equipment.isMonitoringEnabled()) {
        tally.outOfSite += group.mesures().size();
        continue;
      }

      List<MeasureInput> fresh =
          group.mesures().stream().filter(m -> !m.collectedAt().isBefore(oldest)).toList();
      tally.tooOld += group.mesures().size() - fresh.size();
      if (fresh.isEmpty()) {
        continue;
      }
      var result = ingestion.ingestBuffered(equipment.getId(), fresh, evaluateFrom);
      tally.stored += result.stored();
      tally.evaluated += result.evaluated();
      tally.opened += result.alertsOpened();
      tally.closed += result.alertsResolved();
    }

    batches.save(
        new ProxyBatch(
            proxyId,
            request.lotId(),
            now,
            tally.stored,
            tally.evaluated,
            tally.opened,
            tally.closed));
    return new ProxyIngestionResult(
        tally.stored,
        tally.evaluated,
        tally.tooOld,
        tally.outOfSite,
        tally.opened,
        tally.closed,
        false);
  }

  /** Compteurs d'un lot. */
  private static final class Tally {
    int stored;
    int evaluated;
    int tooOld;
    int outOfSite;
    int opened;
    int closed;
  }
}

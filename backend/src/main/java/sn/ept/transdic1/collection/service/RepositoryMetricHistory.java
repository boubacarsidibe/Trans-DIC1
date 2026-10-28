package sn.ept.transdic1.collection.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.alerting.service.MetricHistory;
import sn.ept.transdic1.monitoring.repository.MetricRepository;

/** Fournit au moteur d'alertes l'historique des mesures stocké par le module monitoring. */
@Component
class RepositoryMetricHistory implements MetricHistory {
  /** Garde-fou : une fenêtre de confirmation ne doit jamais charger tout l'historique. */
  private static final int MAX_SAMPLES = 2_000;

  private final MetricRepository metrics;

  RepositoryMetricHistory(MetricRepository metrics) {
    this.metrics = metrics;
  }

  @Override
  @Transactional(readOnly = true)
  public List<Sample> samples(UUID equipmentId, String metricKey, Instant from, Instant to) {
    return metrics
        .findByEquipmentIdAndKeyAndCollectedAtBetweenOrderByCollectedAtDesc(
            equipmentId, metricKey, from, to, PageRequest.of(0, MAX_SAMPLES))
        .stream()
        .map(metric -> new Sample(metric.getCollectedAt(), metric.getValue()))
        .toList();
  }
}

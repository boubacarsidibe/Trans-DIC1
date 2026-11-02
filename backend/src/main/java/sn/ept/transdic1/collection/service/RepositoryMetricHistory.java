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
  public List<Sample> samples(
      UUID equipmentId, UUID serviceId, String metricKey, Instant from, Instant to) {
    var page = PageRequest.of(0, MAX_SAMPLES);
    var found =
        serviceId == null
            ? metrics
                .findByEquipmentIdAndServiceIsNullAndKeyAndCollectedAtBetweenOrderByCollectedAtDesc(
                    equipmentId, metricKey, from, to, page)
            : metrics
                .findByEquipmentIdAndServiceIdAndKeyAndCollectedAtBetweenOrderByCollectedAtDesc(
                    equipmentId, serviceId, metricKey, from, to, page);
    return found.stream()
        .map(metric -> new Sample(metric.getCollectedAt(), metric.getValue()))
        .toList();
  }
}

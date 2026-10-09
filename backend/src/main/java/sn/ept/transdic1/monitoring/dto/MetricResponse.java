package sn.ept.transdic1.monitoring.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import sn.ept.transdic1.monitoring.domain.Metric;

public record MetricResponse(
    UUID id,
    UUID equipmentId,
    String equipmentName,
    String key,
    BigDecimal value,
    String unit,
    String quality,
    Instant collectedAt) {

  public static MetricResponse from(Metric metric) {
    return new MetricResponse(
        metric.getId(),
        metric.getEquipment().getId(),
        metric.getEquipment().getName(),
        metric.getKey(),
        metric.getValue(),
        metric.getUnit(),
        metric.getQuality(),
        metric.getCollectedAt());
  }
}

package sn.ept.transdic1.monitoring.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Historique d'un indicateur sur une période, prêt à tracer. Chaque point résume un intervalle de
 * temps (moyenne, minimum, maximum et nombre de mesures) ; en pas {@code RAW}, un point est une
 * mesure.
 */
public record MetricHistoryResponse(
    UUID equipmentId,
    String key,
    String unit,
    Instant from,
    Instant to,
    String step,
    List<Point> points) {

  public record Point(Instant at, BigDecimal avg, BigDecimal min, BigDecimal max, int count) {}
}

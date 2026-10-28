package sn.ept.transdic1.reporting.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Disponibilité des équipements supervisés sur une période : le temps d'indisponibilité est celui
 * pendant lequel une alerte de collecte était ouverte (l'équipement ou son agent ne répondait
 * plus).
 */
public record AvailabilityReport(
    Instant from,
    Instant to,
    Instant generatedAt,
    long periodSeconds,
    BigDecimal overallAvailabilityPercent,
    List<Row> rows) {

  public record Row(
      UUID equipmentId,
      String equipmentName,
      String siteName,
      long downtimeSeconds,
      int outages,
      BigDecimal availabilityPercent) {}
}

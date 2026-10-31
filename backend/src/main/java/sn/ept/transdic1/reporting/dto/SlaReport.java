package sn.ept.transdic1.reporting.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Disponibilité et respect du SLA par site, comparée à une période de référence. La méthode de
 * calcul est rendue avec le rapport pour qu'il reste lisible une fois exporté.
 */
public record SlaReport(
    Instant from,
    Instant to,
    Instant compareFrom,
    Instant compareTo,
    Instant generatedAt,
    String method,
    List<SiteRow> sites) {

  /**
   * @param delta écart en points entre la période et la référence (positif : mieux)
   * @param met vrai si la disponibilité atteint la cible
   * @param degraded vrai si l'écart est inférieur à −0,1 point
   */
  public record SiteRow(
      UUID siteId,
      String siteName,
      BigDecimal target,
      BigDecimal availability,
      BigDecimal previousAvailability,
      BigDecimal delta,
      boolean met,
      boolean degraded,
      List<EquipmentRow> equipments) {}

  public record EquipmentRow(
      UUID equipmentId,
      String equipmentName,
      long downtimeSeconds,
      long maintenanceSeconds,
      BigDecimal availability,
      BigDecimal previousAvailability) {}
}

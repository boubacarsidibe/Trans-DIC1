package sn.ept.transdic1.overview.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Tout ce que l'écran d'accueil affiche, en une seule lecture cohérente. */
public record DashboardResponse(
    Instant generatedAt, List<Host> hosts, List<Problem> problems, List<HourlyCount> activity) {

  /** Un équipement : son état, sa pire alerte active et ses dernières mesures. */
  public record Host(
      UUID id,
      String name,
      String type,
      UUID siteId,
      String site,
      String status,
      String worstSeverity,
      int activeAlerts,
      BigDecimal cpu,
      BigDecimal memory,
      BigDecimal disk,
      Instant lastSeen) {}

  /**
   * Une alerte active, cause d'origine seulement : les alertes regroupées restent sous leur racine.
   */
  public record Problem(
      UUID id,
      UUID equipmentId,
      String equipment,
      String severity,
      String state,
      String message,
      Instant triggeredAt) {}

  /** Nombre d'alertes ouvertes pendant une heure donnée. */
  public record HourlyCount(Instant hour, long opened) {}
}

package sn.ept.transdic1.overview.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Tout ce que l'écran d'accueil affiche, en une seule lecture cohérente. */
public record DashboardResponse(
    Instant generatedAt,
    List<Host> hosts,
    List<Problem> problems,
    List<HourlyCount> activity,
    List<Forecast> forecasts,
    List<LoadPoint> loadTrend,
    List<SiteHealth> siteHealth) {

  /**
   * Un équipement : son état, sa pire alerte active, ses dernières mesures et son score de santé (0
   * à 100, null en maintenance) avec ce qui lui a coûté des points.
   */
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
      Instant lastSeen,
      Integer health,
      String healthLevel,
      List<String> healthReasons) {}

  /** Sante moyenne d'un site : la moyenne de ses equipements juges. */
  public record SiteHealth(
      UUID siteId, String site, Integer health, String healthLevel, int equipments) {}

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

  /** Disque qui se remplit : à ce rythme, il sera plein dans {@code daysLeft} jours. */
  public record Forecast(
      UUID equipmentId, String equipment, String disk, BigDecimal usage, BigDecimal daysLeft) {}

  /** Charge moyenne du parc sur un quart d'heure ; chaque équipement pèse autant que les autres. */
  public record LoadPoint(Instant at, BigDecimal cpu, BigDecimal memory) {}

  /** Nombre d'alertes ouvertes pendant une heure donnée. */
  public record HourlyCount(Instant hour, long opened) {}
}

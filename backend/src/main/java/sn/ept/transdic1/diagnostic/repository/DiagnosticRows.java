package sn.ept.transdic1.diagnostic.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Lignes lues par {@link DiagnosticRepository} : des faits bruts, sans interprétation. */
public final class DiagnosticRows {
  private DiagnosticRows() {}

  /** L'alerte diagnostiquée, son équipement, sa règle et son instant d'ouverture retenu. */
  public record AlertFacts(
      UUID id,
      UUID equipmentId,
      String equipmentName,
      String equipmentType,
      UUID siteId,
      String siteName,
      UUID ruleId,
      String ruleName,
      String metricType,
      String operator,
      Double threshold,
      String unit,
      UUID eventRuleId,
      String eventRuleName,
      UUID serviceId,
      String type,
      String state,
      String message,
      Instant triggeredAt,
      Instant resolvedAt,
      UUID parentAlertId,
      String correlationReason,
      String occurrenceKey,
      Instant openedAt,
      UUID parentSiteId) {}

  /** Une page plafonnée et le total réel. */
  public record Page<T>(List<T> items, int total) {}

  public record Node(
      UUID id, String name, String type, String siteName, int depth, boolean inAlert) {}

  public record ServiceRow(UUID id, String name, UUID equipmentId, String equipmentName) {}

  /** Alerte voisine ; {@code relation} : 0 même équipement, 1 amont, 2 aval, 3 même site. */
  public record NeighbourRow(
      UUID id,
      UUID equipmentId,
      String equipmentName,
      String type,
      String severity,
      String state,
      String message,
      Instant triggeredAt,
      int relation) {}

  public record UpstreamAlertRow(
      UUID id,
      UUID equipmentId,
      String equipmentName,
      int depth,
      String type,
      String message,
      Instant triggeredAt) {}

  public record OwnAlertRow(
      UUID id, String type, String message, Instant triggeredAt, String occurrenceKey) {}

  public record EventRow(
      UUID id,
      UUID equipmentId,
      String equipmentName,
      String source,
      String severity,
      String title,
      String trapOid,
      Instant receivedAt,
      boolean upstream) {}

  /**
   * Entrée d'audit d'une action de la liste blanche. Les détails ne sont jamais lus : seuls
   * l'action, l'auteur, l'heure et le nom de la cible sortent de la base.
   */
  public record ChangeRow(
      String action,
      String username,
      Instant performedAt,
      String targetType,
      UUID targetId,
      String targetName,
      UUID targetSiteId,
      boolean upstreamEquipment) {}

  public record MetricValue(Instant at, double value, String unit) {}

  public record Reference(Double average, int days) {}

  public record SimilarStats(int count, int resolved, Double median, Double mean) {}

  public record ResolutionRow(
      UUID id,
      Instant triggeredAt,
      Instant resolvedAt,
      String resolutionType,
      String comment,
      String author) {}
}

package sn.ept.transdic1.risk.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Lectures du modèle de risque : comptes d'alertes et pics d'occupation, agrégés par la base (par
 * équipement et par jour, heure de Dakar), pour qu'un historique de deux mois tienne en quelques
 * milliers de lignes. Les pics viennent des résumés horaires ({@code metric_hourly}), gardés 13
 * mois, et non des mesures brutes.
 */
@Repository
public class RiskRepository {
  private static final String ZONE = "Africa/Dakar";
  private static final String METRICS =
      "(metric_key IN ('cpu.usage', 'memory.usage') OR metric_key LIKE 'disk.usage%')";
  private static final String FAMILY =
      "CASE WHEN metric_key = 'cpu.usage' THEN 'cpu' WHEN metric_key = 'memory.usage' THEN 'memory'"
          + " ELSE 'disk' END";

  private final JdbcTemplate jdbc;

  public RiskRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public record EquipmentRow(UUID id, String name, UUID siteId, String siteName) {}

  /** Alertes d'un équipement, groupées ; {@code day} est nul pour une fenêtre glissante. */
  public record AlertCount(
      UUID equipmentId, LocalDate day, String type, String severity, boolean flapping, int count) {}

  public record MetricPeak(UUID equipmentId, LocalDate day, String family, double max) {}

  public record StoredModel(
      Instant trainedAt,
      int samples,
      int positives,
      String mode,
      Double auc,
      Double brier,
      double baseRate,
      int evaluationSamples,
      String weightsJson) {}

  public List<EquipmentRow> equipments() {
    return jdbc.query(
        "SELECT e.id, e.name, e.site_id, s.name FROM equipment e"
            + " LEFT JOIN site s ON s.id = e.site_id WHERE e.monitoring_enabled ORDER BY e.name",
        (rs, n) ->
            new EquipmentRow(
                rs.getObject(1, UUID.class),
                rs.getString(2),
                rs.getObject(3, UUID.class),
                rs.getString(4)));
  }

  public List<AlertCount> dailyAlerts(Instant from, Instant to) {
    return jdbc.query(
        "SELECT equipment_id, (triggered_at AT TIME ZONE '"
            + ZONE
            + "')::date, type, severity,"
            + " flapping_since IS NOT NULL, count(*) FROM alert"
            + " WHERE triggered_at >= ? AND triggered_at < ? AND state <> 'DELETED'"
            + " GROUP BY 1, 2, 3, 4, 5",
        (rs, n) ->
            new AlertCount(
                rs.getObject(1, UUID.class),
                rs.getObject(2, LocalDate.class),
                rs.getString(3),
                rs.getString(4),
                rs.getBoolean(5),
                rs.getInt(6)),
        Timestamp.from(from),
        Timestamp.from(to));
  }

  public List<AlertCount> windowAlerts(Instant from, Instant to) {
    return jdbc.query(
        "SELECT equipment_id, type, severity, flapping_since IS NOT NULL, count(*) FROM alert"
            + " WHERE triggered_at >= ? AND triggered_at < ? AND state <> 'DELETED'"
            + " GROUP BY 1, 2, 3, 4",
        (rs, n) ->
            new AlertCount(
                rs.getObject(1, UUID.class),
                null,
                rs.getString(2),
                rs.getString(3),
                rs.getBoolean(4),
                rs.getInt(5)),
        Timestamp.from(from),
        Timestamp.from(to));
  }

  public List<MetricPeak> dailyPeaks(Instant from, Instant to) {
    return jdbc.query(
        "SELECT equipment_id, (hour_start AT TIME ZONE '"
            + ZONE
            + "')::date, "
            + FAMILY
            + ","
            + " max(max_value) FROM metric_hourly WHERE hour_start >= ? AND hour_start < ? AND "
            + METRICS
            + " GROUP BY 1, 2, 3",
        (rs, n) ->
            new MetricPeak(
                rs.getObject(1, UUID.class),
                rs.getObject(2, LocalDate.class),
                rs.getString(3),
                rs.getDouble(4)),
        Timestamp.from(from),
        Timestamp.from(to));
  }

  /**
   * Pics sur une fenêtre glissante : résumés horaires complets, plus les mesures brutes de l'heure
   * en cours (pas encore résumée), pour que le score suive ce qui se passe maintenant.
   */
  public List<MetricPeak> windowPeaks(Instant from, Instant to) {
    return jdbc.query(
        "SELECT equipment_id, family, max(v) FROM ("
            + " SELECT equipment_id, "
            + FAMILY
            + " AS family, max_value AS v FROM metric_hourly"
            + " WHERE hour_start >= ? AND hour_start < ? AND "
            + METRICS
            + " UNION ALL SELECT equipment_id, "
            + FAMILY
            + " AS family, metric_value AS v"
            + " FROM metric WHERE collected_at >= ? AND collected_at < ? AND "
            + METRICS
            + ") x GROUP BY 1, 2",
        (rs, n) ->
            new MetricPeak(rs.getObject(1, UUID.class), null, rs.getString(2), rs.getDouble(3)),
        Timestamp.from(from),
        Timestamp.from(to),
        Timestamp.from(to.minusSeconds(3600)),
        Timestamp.from(to));
  }

  public Optional<StoredModel> model() {
    return jdbc
        .query(
            "SELECT trained_at, samples, positives, mode, auc, brier, base_rate,"
                + " evaluation_samples, weights_json FROM risk_model WHERE id = 1",
            (rs, n) ->
                new StoredModel(
                    rs.getTimestamp(1).toInstant(),
                    rs.getInt(2),
                    rs.getInt(3),
                    rs.getString(4),
                    (Double) (rs.getObject(5) == null ? null : rs.getDouble(5)),
                    (Double) (rs.getObject(6) == null ? null : rs.getDouble(6)),
                    rs.getDouble(7),
                    rs.getInt(8),
                    rs.getString(9)))
        .stream()
        .findFirst();
  }

  public void save(StoredModel m) {
    jdbc.update(
        "INSERT INTO risk_model(id, trained_at, samples, positives, mode, auc, brier, base_rate,"
            + " evaluation_samples, weights_json) VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
            + " ON CONFLICT (id) DO UPDATE SET trained_at = excluded.trained_at,"
            + " samples = excluded.samples, positives = excluded.positives, mode = excluded.mode,"
            + " auc = excluded.auc, brier = excluded.brier, base_rate = excluded.base_rate,"
            + " evaluation_samples = excluded.evaluation_samples,"
            + " weights_json = excluded.weights_json",
        Timestamp.from(m.trainedAt()),
        m.samples(),
        m.positives(),
        m.mode(),
        m.auc(),
        m.brier(),
        m.baseRate(),
        m.evaluationSamples(),
        m.weightsJson());
  }
}

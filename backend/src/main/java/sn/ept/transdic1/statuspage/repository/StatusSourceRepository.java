package sn.ept.transdic1.statuspage.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import sn.ept.transdic1.statuspage.domain.ComponentSourceType;

/**
 * Lecture bornée de l'état supervisé des sources des composants (ADR 0026) : requêtes SQL propres
 * au module, qui ne lisent que les colonnes nécessaires au calcul. Aucune adresse, aucun nom d'hôte
 * ni port n'est lu ici, sauf pour le libellé d'une source montré aux administrateurs ({@link
 * #labels}).
 */
@Repository
public class StatusSourceRepository {
  /** Recherche de sources : nombre de propositions au plus. */
  public static final int MAX_OPTIONS = 50;

  private static final List<String> ACTIVE_ALERT_STATES = List.of("OPEN", "IN_PROGRESS");

  private final NamedParameterJdbcTemplate jdbc;

  public StatusSourceRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Faits d'une sonde. */
  public record ProbeRow(
      UUID id,
      UUID equipmentId,
      UUID siteId,
      boolean active,
      boolean equipmentMonitored,
      Boolean lastUp,
      Instant lastCheckedAt,
      int consecutiveFailures,
      int intervalSeconds) {}

  /** Faits d'un équipement. */
  public record EquipmentRow(UUID id, UUID siteId, boolean monitored, String status) {}

  /** Alerte active : équipement, service éventuel, type et sévérité. */
  public record AlertRow(UUID equipmentId, UUID serviceId, String type, String severity) {}

  /** Maintenance non annulée : cible (équipement ou site) et fenêtre. */
  public record MaintenanceRow(UUID equipmentId, UUID siteId, Instant start, Instant end) {}

  /** Mesures {@code probe.up} d'une heure pour un équipement. */
  public record HourRow(UUID equipmentId, Instant hourStart, long samples, double sum) {}

  /** Proposition de source pour l'administrateur. */
  public record SourceRow(UUID id, String label, String detail) {}

  public Map<UUID, ProbeRow> probes(Collection<UUID> ids) {
    var result = new HashMap<UUID, ProbeRow>();
    if (ids.isEmpty()) {
      return result;
    }
    jdbc.query(
        "SELECT s.id, s.equipment_id, e.site_id, s.active, e.monitoring_enabled, s.last_up,"
            + " s.last_checked_at, s.consecutive_failures, s.interval_seconds"
            + " FROM monitored_service s JOIN equipment e ON e.id = s.equipment_id"
            + " WHERE s.id IN (:ids)",
        new MapSqlParameterSource("ids", ids),
        rs -> {
          var id = rs.getObject("id", UUID.class);
          Timestamp checked = rs.getTimestamp("last_checked_at");
          result.put(
              id,
              new ProbeRow(
                  id,
                  rs.getObject("equipment_id", UUID.class),
                  rs.getObject("site_id", UUID.class),
                  rs.getBoolean("active"),
                  rs.getBoolean("monitoring_enabled"),
                  (Boolean) rs.getObject("last_up"),
                  checked == null ? null : checked.toInstant(),
                  rs.getInt("consecutive_failures"),
                  rs.getInt("interval_seconds")));
        });
    return result;
  }

  public Map<UUID, EquipmentRow> equipments(Collection<UUID> ids) {
    return equipmentsWhere("id IN (:ids)", ids);
  }

  public Map<UUID, EquipmentRow> equipmentsOfSites(Collection<UUID> siteIds) {
    return equipmentsWhere("site_id IN (:ids)", siteIds);
  }

  private Map<UUID, EquipmentRow> equipmentsWhere(String condition, Collection<UUID> ids) {
    var result = new HashMap<UUID, EquipmentRow>();
    if (ids.isEmpty()) {
      return result;
    }
    jdbc.query(
        "SELECT id, site_id, monitoring_enabled, operational_status FROM equipment WHERE "
            + condition,
        new MapSqlParameterSource("ids", ids),
        rs -> {
          var id = rs.getObject("id", UUID.class);
          result.put(
              id,
              new EquipmentRow(
                  id,
                  rs.getObject("site_id", UUID.class),
                  rs.getBoolean("monitoring_enabled"),
                  rs.getString("operational_status")));
        });
    return result;
  }

  /** Sites existants parmi {@code ids}. */
  public List<UUID> existingSites(Collection<UUID> ids) {
    if (ids.isEmpty()) {
      return List.of();
    }
    return jdbc.queryForList(
        "SELECT id FROM site WHERE id IN (:ids)",
        new MapSqlParameterSource("ids", ids),
        UUID.class);
  }

  /** Alertes actives des équipements donnés. */
  public List<AlertRow> activeAlerts(Collection<UUID> equipmentIds) {
    if (equipmentIds.isEmpty()) {
      return List.of();
    }
    return jdbc.query(
        "SELECT equipment_id, service_id, type, severity FROM alert"
            + " WHERE state IN (:states) AND equipment_id IN (:ids)",
        new MapSqlParameterSource("ids", equipmentIds).addValue("states", ACTIVE_ALERT_STATES),
        (rs, i) ->
            new AlertRow(
                rs.getObject("equipment_id", UUID.class),
                rs.getObject("service_id", UUID.class),
                rs.getString("type"),
                rs.getString("severity")));
  }

  /**
   * Maintenances planifiées ou en cours (ni annulées ni terminées) qui chevauchent [from, to[ et
   * visent ces équipements ou ces sites. Pour la disponibilité passée, {@code includeCompleted}
   * garde aussi les maintenances terminées.
   */
  public List<MaintenanceRow> maintenances(
      Collection<UUID> equipmentIds,
      Collection<UUID> siteIds,
      Instant from,
      Instant to,
      boolean includeCompleted) {
    if (equipmentIds.isEmpty() && siteIds.isEmpty()) {
      return List.of();
    }
    var states =
        includeCompleted
            ? List.of("PLANNED", "IN_PROGRESS", "COMPLETED")
            : List.of("PLANNED", "IN_PROGRESS");
    var params =
        new MapSqlParameterSource("states", states)
            .addValue("from", Timestamp.from(from))
            .addValue("to", Timestamp.from(to))
            .addValue("equipments", equipmentIds.isEmpty() ? List.of(new UUID(0, 0)) : equipmentIds)
            .addValue("sites", siteIds.isEmpty() ? List.of(new UUID(0, 0)) : siteIds);
    return jdbc.query(
        "SELECT equipment_id, site_id, start_at, end_at FROM maintenance"
            + " WHERE state IN (:states) AND start_at < :to AND end_at > :from"
            + " AND (equipment_id IN (:equipments) OR site_id IN (:sites))",
        params,
        (rs, i) ->
            new MaintenanceRow(
                rs.getObject("equipment_id", UUID.class),
                rs.getObject("site_id", UUID.class),
                rs.getTimestamp("start_at").toInstant(),
                rs.getTimestamp("end_at").toInstant()));
  }

  /** Mesures brutes {@code probe.up} d'une sonde, par heure, depuis {@code from}. */
  public List<HourRow> rawProbeHours(UUID equipmentId, UUID serviceId, Instant from) {
    return hours(
        "SELECT equipment_id,"
            + " date_trunc('hour', collected_at AT TIME ZONE 'UTC') AT TIME ZONE 'UTC' AS h,"
            + " count(*) AS n, sum(metric_value) AS s FROM metric"
            + " WHERE equipment_id = :equipment AND metric_key = 'probe.up' AND service_id = :service"
            + " AND collected_at >= :from AND quality <> 'BAD' GROUP BY 1, 2",
        new MapSqlParameterSource("equipment", equipmentId)
            .addValue("service", serviceId)
            .addValue("from", Timestamp.from(from)));
  }

  /** Mesures brutes {@code probe.up} de toutes les sondes de ces équipements, par heure. */
  public List<HourRow> rawEquipmentHours(Collection<UUID> equipmentIds, Instant from) {
    if (equipmentIds.isEmpty()) {
      return List.of();
    }
    return hours(
        "SELECT equipment_id,"
            + " date_trunc('hour', collected_at AT TIME ZONE 'UTC') AT TIME ZONE 'UTC' AS h,"
            + " count(*) AS n, sum(metric_value) AS s FROM metric"
            + " WHERE equipment_id IN (:ids) AND metric_key = 'probe.up'"
            + " AND collected_at >= :from AND quality <> 'BAD' GROUP BY 1, 2",
        new MapSqlParameterSource("ids", equipmentIds).addValue("from", Timestamp.from(from)));
  }

  /** Agrégats horaires {@code probe.up} de ces équipements (toutes sondes confondues). */
  public List<HourRow> hourlyEquipmentHours(Collection<UUID> equipmentIds, Instant from) {
    if (equipmentIds.isEmpty()) {
      return List.of();
    }
    return hours(
        "SELECT equipment_id, hour_start AS h, sample_count AS n, avg_value * sample_count AS s"
            + " FROM metric_hourly WHERE equipment_id IN (:ids) AND metric_key = 'probe.up'"
            + " AND hour_start >= :from",
        new MapSqlParameterSource("ids", equipmentIds).addValue("from", Timestamp.from(from)));
  }

  private List<HourRow> hours(String sql, MapSqlParameterSource params) {
    return jdbc.query(
        sql,
        params,
        (rs, i) ->
            new HourRow(
                rs.getObject("equipment_id", UUID.class),
                rs.getTimestamp("h").toInstant(),
                rs.getLong("n"),
                rs.getDouble("s")));
  }

  /** Nombre de services supervisés (sondes) d'un équipement. */
  public int serviceCount(UUID equipmentId) {
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM monitored_service WHERE equipment_id = :id",
            new MapSqlParameterSource("id", equipmentId),
            Integer.class);
    return count == null ? 0 : count;
  }

  /** Vrai si la source existe. */
  public boolean exists(ComponentSourceType type, UUID id) {
    String table =
        switch (type) {
          case PROBE -> "monitored_service";
          case EQUIPMENT -> "equipment";
          case SITE -> "site";
        };
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM " + table + " WHERE id = :id",
            new MapSqlParameterSource("id", id),
            Integer.class);
    return count != null && count > 0;
  }

  /** Site de rattachement d'une source (périmètre d'accès), vide si la source n'existe plus. */
  public Optional<UUID> siteOf(ComponentSourceType type, UUID id) {
    String sql =
        switch (type) {
          case PROBE ->
              "SELECT e.site_id FROM monitored_service s JOIN equipment e ON e.id = s.equipment_id"
                  + " WHERE s.id = :id";
          case EQUIPMENT -> "SELECT site_id FROM equipment WHERE id = :id";
          case SITE -> "SELECT id FROM site WHERE id = :id";
        };
    var rows = jdbc.queryForList(sql, new MapSqlParameterSource("id", id), UUID.class);
    return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
  }

  /**
   * Libellés techniques des sources (administrateurs seulement) : « équipement — sonde » pour une
   * sonde, nom de l'équipement, nom du site.
   */
  public Map<UUID, String> labels(ComponentSourceType type, Collection<UUID> ids) {
    var result = new HashMap<UUID, String>();
    if (ids.isEmpty()) {
      return result;
    }
    String sql =
        switch (type) {
          case PROBE ->
              "SELECT s.id, e.name || ' — ' || s.name AS label FROM monitored_service s"
                  + " JOIN equipment e ON e.id = s.equipment_id WHERE s.id IN (:ids)";
          case EQUIPMENT -> "SELECT id, name AS label FROM equipment WHERE id IN (:ids)";
          case SITE -> "SELECT id, name AS label FROM site WHERE id IN (:ids)";
        };
    jdbc.query(
        sql,
        new MapSqlParameterSource("ids", ids),
        rs -> {
          result.put(rs.getObject("id", UUID.class), rs.getString("label"));
        });
    return result;
  }

  /**
   * Recherche de sources par nom (administrateurs) : au plus {@link #MAX_OPTIONS} propositions,
   * filtrées par le périmètre {@code siteIds} quand il est donné.
   */
  public List<SourceRow> search(ComponentSourceType type, String query, Collection<UUID> siteIds) {
    String like = "%" + escapeLike(query == null ? "" : query.trim().toLowerCase()) + "%";
    var params =
        new MapSqlParameterSource("q", like)
            .addValue("limit", MAX_OPTIONS)
            .addValue(
                "sites", siteIds == null || siteIds.isEmpty() ? List.of(new UUID(0, 0)) : siteIds)
            .addValue("restricted", siteIds != null);
    String sql =
        switch (type) {
          case PROBE ->
              "SELECT s.id, s.name AS label, e.name || ' · ' || si.name AS detail"
                  + " FROM monitored_service s JOIN equipment e ON e.id = s.equipment_id"
                  + " LEFT JOIN site si ON si.id = e.site_id"
                  + " WHERE (lower(s.name) LIKE :q ESCAPE '\\' OR lower(e.name) LIKE :q ESCAPE '\\')"
                  + " AND (NOT :restricted OR e.site_id IN (:sites))"
                  + " ORDER BY e.name, s.name LIMIT :limit";
          case EQUIPMENT ->
              "SELECT e.id, e.name AS label, si.name AS detail FROM equipment e"
                  + " LEFT JOIN site si ON si.id = e.site_id"
                  + " WHERE lower(e.name) LIKE :q ESCAPE '\\'"
                  + " AND (NOT :restricted OR e.site_id IN (:sites))"
                  + " ORDER BY e.name LIMIT :limit";
          case SITE ->
              "SELECT id, name AS label, code AS detail FROM site"
                  + " WHERE (lower(name) LIKE :q ESCAPE '\\' OR lower(code) LIKE :q ESCAPE '\\')"
                  + " AND (NOT :restricted OR id IN (:sites))"
                  + " ORDER BY name LIMIT :limit";
        };
    var rows = new ArrayList<SourceRow>();
    jdbc.query(
        sql,
        params,
        rs -> {
          rows.add(
              new SourceRow(
                  rs.getObject("id", UUID.class), rs.getString("label"), rs.getString("detail")));
        });
    return rows;
  }

  private static String escapeLike(String value) {
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }
}

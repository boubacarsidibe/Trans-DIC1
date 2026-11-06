package sn.ept.transdic1.overview.service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.common.scope.SiteScope;
import sn.ept.transdic1.overview.dto.DashboardResponse;
import sn.ept.transdic1.overview.dto.DashboardResponse.Host;
import sn.ept.transdic1.overview.dto.DashboardResponse.HourlyCount;
import sn.ept.transdic1.overview.dto.DashboardResponse.Problem;

/**
 * Écran d'accueil : tout le parc d'un coup d'œil. Une seule lecture pour que les chiffres d'un
 * widget à l'autre soient cohérents, et des requêtes groupées pour rester rapide sur un grand parc.
 */
@Service
public class DashboardService {
  private static final int MAX_HOSTS = 500;
  private static final int MAX_PROBLEMS = 30;
  private static final Duration RECENT = Duration.ofMinutes(15);
  private static final String ACTIVE = "a.state IN ('OPEN', 'IN_PROGRESS')";
  private static final Map<String, Integer> RANK = Map.of("CRITICAL", 3, "WARNING", 2, "INFO", 1);

  private final NamedParameterJdbcTemplate jdbc;
  private final Clock clock;
  private final SiteScope scope;

  public DashboardService(NamedParameterJdbcTemplate jdbc, Clock clock, SiteScope scope) {
    this.jdbc = jdbc;
    this.clock = clock;
    this.scope = scope;
  }

  private record Severities(String worst, int count) {}

  private record Latest(BigDecimal cpu, BigDecimal memory, BigDecimal disk, Instant seen) {
    Latest with(String key, BigDecimal value, Instant at) {
      Instant last = seen == null || at.isAfter(seen) ? at : seen;
      if (key.equals("cpu.usage")) {
        return new Latest(value, memory, disk, last);
      }
      if (key.equals("memory.usage")) {
        return new Latest(cpu, value, disk, last);
      }
      if (key.startsWith("disk.usage")) {
        // Le disque le plus rempli : c'est lui qui menace le serveur.
        BigDecimal worst = disk == null || value.compareTo(disk) > 0 ? value : disk;
        return new Latest(cpu, memory, worst, last);
      }
      return new Latest(cpu, memory, disk, last);
    }
  }

  @Transactional(readOnly = true)
  public DashboardResponse dashboard() {
    Instant now = clock.instant();
    var params = new MapSqlParameterSource();
    String sites = "";
    var restriction = scope.restriction();
    if (restriction.isPresent()) {
      if (restriction.get().isEmpty()) {
        return new DashboardResponse(now, List.of(), List.of(), List.of());
      }
      params.addValue("sites", restriction.get());
      sites = " AND e.site_id IN (:sites)";
    }
    params.addValue("recent", Timestamp.from(now.minus(RECENT)));
    params.addValue("day", Timestamp.from(now.minus(Duration.ofHours(24))));
    params.addValue("hosts", MAX_HOSTS);
    params.addValue("problems", MAX_PROBLEMS);

    Map<UUID, Severities> severities = severities(params, sites);
    Map<UUID, Latest> latest = latest(params, sites);
    return new DashboardResponse(
        now,
        hosts(params, sites, severities, latest),
        problems(params, sites),
        activity(params, sites, now));
  }

  private Map<UUID, Severities> severities(MapSqlParameterSource params, String sites) {
    Map<UUID, Severities> found = new HashMap<>();
    jdbc.query(
        "SELECT a.equipment_id, a.severity, count(*) AS total FROM alert a"
            + " JOIN equipment e ON e.id = a.equipment_id WHERE "
            + ACTIVE
            + sites
            + " GROUP BY a.equipment_id, a.severity",
        params,
        rs -> {
          UUID id = rs.getObject(1, UUID.class);
          String severity = rs.getString(2);
          int count = rs.getInt(3);
          found.merge(
              id,
              new Severities(severity, count),
              (a, b) ->
                  new Severities(
                      RANK.getOrDefault(b.worst(), 0) > RANK.getOrDefault(a.worst(), 0)
                          ? b.worst()
                          : a.worst(),
                      a.count() + b.count()));
        });
    return found;
  }

  private Map<UUID, Latest> latest(MapSqlParameterSource params, String sites) {
    Map<UUID, Latest> found = new HashMap<>();
    jdbc.query(
        "SELECT DISTINCT ON (m.equipment_id, m.metric_key)"
            + " m.equipment_id, m.metric_key, m.metric_value, m.collected_at"
            + " FROM metric m JOIN equipment e ON e.id = m.equipment_id"
            + " WHERE m.collected_at >= :day AND m.service_id IS NULL"
            + " AND (m.metric_key IN ('cpu.usage', 'memory.usage', 'disk.usage', 'availability')"
            + " OR m.metric_key LIKE 'disk.usage[%')"
            + sites
            + " ORDER BY m.equipment_id, m.metric_key, m.collected_at DESC",
        params,
        rs -> {
          Instant at = rs.getTimestamp(4).toInstant();
          found.merge(
              rs.getObject(1, UUID.class),
              new Latest(null, null, null, null).with(rs.getString(2), rs.getBigDecimal(3), at),
              DashboardService::merge);
        });
    return found;
  }

  private static Latest merge(Latest a, Latest b) {
    return new Latest(
        b.cpu() != null ? b.cpu() : a.cpu(),
        b.memory() != null ? b.memory() : a.memory(),
        a.disk() == null || (b.disk() != null && b.disk().compareTo(a.disk()) > 0)
            ? b.disk()
            : a.disk(),
        a.seen() == null || (b.seen() != null && b.seen().isAfter(a.seen())) ? b.seen() : a.seen());
  }

  private List<Host> hosts(
      MapSqlParameterSource params,
      String sites,
      Map<UUID, Severities> severities,
      Map<UUID, Latest> latest) {
    return jdbc.query(
        "SELECT e.id, e.name, e.equipment_type, e.operational_status, s.id AS site_id,"
            + " s.name AS site FROM equipment e JOIN site s ON s.id = e.site_id WHERE 1 = 1"
            + sites
            + " ORDER BY e.name LIMIT :hosts",
        params,
        (rs, row) -> {
          UUID id = rs.getObject(1, UUID.class);
          var severity = severities.get(id);
          var values = latest.getOrDefault(id, new Latest(null, null, null, null));
          return new Host(
              id,
              rs.getString(2),
              rs.getString(3),
              rs.getObject(5, UUID.class),
              rs.getString(6),
              rs.getString(4),
              severity == null ? null : severity.worst(),
              severity == null ? 0 : severity.count(),
              values.cpu(),
              values.memory(),
              values.disk(),
              values.seen());
        });
  }

  private List<Problem> problems(MapSqlParameterSource params, String sites) {
    return jdbc.query(
        "SELECT a.id, e.id, e.name, a.severity, a.state, a.message, a.triggered_at FROM alert a"
            + " JOIN equipment e ON e.id = a.equipment_id WHERE "
            + ACTIVE
            + " AND a.parent_alert_id IS NULL"
            + sites
            + " ORDER BY CASE a.severity WHEN 'CRITICAL' THEN 0 WHEN 'WARNING' THEN 1 ELSE 2 END,"
            + " a.triggered_at DESC LIMIT :problems",
        params,
        (rs, row) ->
            new Problem(
                rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class),
                rs.getString(3),
                rs.getString(4),
                rs.getString(5),
                rs.getString(6),
                rs.getTimestamp(7).toInstant()));
  }

  private List<HourlyCount> activity(MapSqlParameterSource params, String sites, Instant now) {
    Map<Long, Long> counted = new HashMap<>();
    jdbc.query(
        "SELECT date_trunc('hour', a.triggered_at) AS hour, count(*) FROM alert a"
            + " JOIN equipment e ON e.id = a.equipment_id WHERE a.triggered_at >= :day"
            + sites
            + " GROUP BY 1",
        params,
        rs -> {
          counted.put(rs.getTimestamp(1).toInstant().getEpochSecond() / 3600, rs.getLong(2));
        });
    // Toutes les heures, même sans alerte : un trou dans le graphe serait trompeur.
    long current = now.getEpochSecond() / 3600;
    List<HourlyCount> hours = new ArrayList<>();
    for (long hour = current - 23; hour <= current; hour++) {
      hours.add(
          new HourlyCount(Instant.ofEpochSecond(hour * 3600), counted.getOrDefault(hour, 0L)));
    }
    return hours;
  }
}

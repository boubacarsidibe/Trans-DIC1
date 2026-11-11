package sn.ept.transdic1.diagnostic.repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import sn.ept.transdic1.diagnostic.repository.DiagnosticRows.AlertFacts;
import sn.ept.transdic1.diagnostic.repository.DiagnosticRows.ChangeRow;
import sn.ept.transdic1.diagnostic.repository.DiagnosticRows.EventRow;
import sn.ept.transdic1.diagnostic.repository.DiagnosticRows.MetricValue;
import sn.ept.transdic1.diagnostic.repository.DiagnosticRows.NeighbourRow;
import sn.ept.transdic1.diagnostic.repository.DiagnosticRows.Node;
import sn.ept.transdic1.diagnostic.repository.DiagnosticRows.OwnAlertRow;
import sn.ept.transdic1.diagnostic.repository.DiagnosticRows.Page;
import sn.ept.transdic1.diagnostic.repository.DiagnosticRows.Reference;
import sn.ept.transdic1.diagnostic.repository.DiagnosticRows.ResolutionRow;
import sn.ept.transdic1.diagnostic.repository.DiagnosticRows.ServiceRow;
import sn.ept.transdic1.diagnostic.repository.DiagnosticRows.SimilarStats;
import sn.ept.transdic1.diagnostic.repository.DiagnosticRows.UpstreamAlertRow;

/**
 * Lectures du diagnostic (ADR 0024) : des requêtes SQL bornées (LIMIT), chacune servie par un index
 * existant (alert(triggered_at), alert(equipment_id, state), alert(rule_id),
 * alert(parent_alert_id), network_event(equipment_id, received_at), audit_entry(performed_at),
 * metric(equipment_id, metric_key, collected_at), equipment_link(upstream_id) et sa contrainte
 * unique), sans N+1 : une requête par rubrique, quel que soit le nombre de lignes.
 *
 * <p>Le périmètre par site (ADR 0018) est appliqué dans la requête ({@code site_id IN (...)}) pour
 * que les plafonds s'appliquent aux seules lignes visibles.
 */
@Repository
public class DiagnosticRepository {
  /** Au-delà, la topologie est jugée incohérente (même borne que TopologyService). */
  private static final int MAX_DEPTH = 64;

  /** Amont et aval récursifs de l'équipement (deux paramètres : l'équipement, deux fois). */
  private static final String GRAPH =
      "WITH RECURSIVE up(id, depth) AS ("
          + " SELECT upstream_id, 1 FROM equipment_link WHERE equipment_id = ?"
          + " UNION SELECT l.upstream_id, u.depth + 1 FROM equipment_link l"
          + " JOIN up u ON l.equipment_id = u.id WHERE u.depth < "
          + MAX_DEPTH
          + "), down(id, depth) AS ("
          + " SELECT equipment_id, 1 FROM equipment_link WHERE upstream_id = ?"
          + " UNION SELECT l.equipment_id, d.depth + 1 FROM equipment_link l"
          + " JOIN down d ON l.upstream_id = d.id WHERE d.depth < "
          + MAX_DEPTH
          + "), nearest_up AS (SELECT id, min(depth) AS depth FROM up GROUP BY id),"
          + " nearest_down AS (SELECT id, min(depth) AS depth FROM down GROUP BY id) ";

  /** Remplace un identifiant absent : une égalité avec lui est toujours fausse. */
  private static final UUID NONE = new UUID(0L, 0L);

  private final JdbcTemplate jdbc;

  public DiagnosticRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  // ------------------------------------------------------------------ alerte

  public Optional<AlertFacts> alert(UUID id) {
    return jdbc
        .query(
            "SELECT a.id, a.equipment_id, e.name, e.equipment_type, e.site_id, s.name,"
                + " a.rule_id, r.name, r.metric_type, r.operator, r.threshold, r.unit,"
                + " a.event_rule_id, er.name, a.service_id, a.type, a.state, a.message,"
                + " a.triggered_at, a.resolved_at, a.parent_alert_id, a.correlation_reason,"
                + " a.occurrence_key,"
                + " COALESCE((SELECT max(ev.occurred_at) FROM alert_event ev"
                + " WHERE ev.alert_id = a.id AND ev.type = 'OPENED'), a.triggered_at),"
                + " pe.site_id"
                + " FROM alert a JOIN equipment e ON e.id = a.equipment_id"
                + " JOIN site s ON s.id = e.site_id"
                + " LEFT JOIN alert_rule r ON r.id = a.rule_id"
                + " LEFT JOIN event_rule er ON er.id = a.event_rule_id"
                + " LEFT JOIN alert pa ON pa.id = a.parent_alert_id"
                + " LEFT JOIN equipment pe ON pe.id = pa.equipment_id"
                + " WHERE a.id = ? AND a.state <> 'DELETED'",
            (rs, i) ->
                new AlertFacts(
                    uuid(rs, 1),
                    uuid(rs, 2),
                    rs.getString(3),
                    rs.getString(4),
                    uuid(rs, 5),
                    rs.getString(6),
                    uuid(rs, 7),
                    rs.getString(8),
                    rs.getString(9),
                    rs.getString(10),
                    number(rs.getBigDecimal(11)),
                    rs.getString(12),
                    uuid(rs, 13),
                    rs.getString(14),
                    uuid(rs, 15),
                    rs.getString(16),
                    rs.getString(17),
                    rs.getString(18),
                    instant(rs, 19),
                    instant(rs, 20),
                    uuid(rs, 21),
                    rs.getString(22),
                    rs.getString(23),
                    instant(rs, 24),
                    uuid(rs, 25)),
            id)
        .stream()
        .findFirst();
  }

  // ------------------------------------------------------------------ impact

  /** Équipements en aval visibles, les plus proches d'abord, et leur total. */
  public Page<Node> downstream(UUID equipmentId, Optional<Set<UUID>> visible, int limit) {
    var scope = inSites("e.site_id", visible);
    var params = graphParams(equipmentId);
    params.addAll(scope.params());
    params.add(limit);
    var rows =
        jdbc.query(
            GRAPH
                + "SELECT e.id, e.name, e.equipment_type, s.name, n.depth,"
                + " EXISTS (SELECT 1 FROM alert x WHERE x.equipment_id = e.id"
                + " AND x.state IN ('OPEN', 'IN_PROGRESS')), count(*) OVER ()"
                + " FROM nearest_down n JOIN equipment e ON e.id = n.id"
                + " JOIN site s ON s.id = e.site_id WHERE TRUE"
                + scope.sql()
                + " ORDER BY n.depth, e.name, e.id LIMIT ?",
            (rs, i) ->
                new Counted<>(
                    new Node(
                        uuid(rs, 1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getString(4),
                        rs.getInt(5),
                        rs.getBoolean(6)),
                    rs.getInt(7)),
            params.toArray());
    return page(rows);
  }

  /** Services actifs de l'équipement et de son aval visible, et leur total. */
  public Page<ServiceRow> services(UUID equipmentId, Optional<Set<UUID>> visible, int limit) {
    var scope = inSites("e.site_id", visible);
    var params = graphParams(equipmentId);
    params.add(equipmentId);
    params.addAll(scope.params());
    params.add(equipmentId);
    params.add(limit);
    var rows =
        jdbc.query(
            GRAPH
                + "SELECT ms.id, ms.name, e.id, e.name, count(*) OVER ()"
                + " FROM monitored_service ms JOIN equipment e ON e.id = ms.equipment_id"
                + " WHERE ms.active AND (ms.equipment_id = ?"
                + " OR ms.equipment_id IN (SELECT id FROM nearest_down))"
                + scope.sql()
                + " ORDER BY (ms.equipment_id = ?) DESC, e.name, ms.name, ms.id LIMIT ?",
            (rs, i) ->
                new Counted<>(
                    new ServiceRow(uuid(rs, 1), rs.getString(2), uuid(rs, 3), rs.getString(4)),
                    rs.getInt(5)),
            params.toArray());
    return page(rows);
  }

  /** Sites de l'équipement et de son aval visible. */
  public List<String> sites(UUID equipmentId, Optional<Set<UUID>> visible, int limit) {
    var scope = inSites("e.site_id", visible);
    var params = graphParams(equipmentId);
    params.add(equipmentId);
    params.addAll(scope.params());
    params.add(limit);
    return jdbc.query(
        GRAPH
            + "SELECT DISTINCT s.name FROM equipment e JOIN site s ON s.id = e.site_id"
            + " WHERE (e.id = ? OR e.id IN (SELECT id FROM nearest_down))"
            + scope.sql()
            + " ORDER BY s.name LIMIT ?",
        (rs, i) -> rs.getString(1),
        params.toArray());
  }

  /** Alertes rattachées à celle-ci par le regroupement (ADR 0014), visibles. */
  public int groupedChildren(UUID alertId, Optional<Set<UUID>> visible) {
    var scope = inSites("e.site_id", visible);
    var params = new ArrayList<Object>();
    params.add(alertId);
    params.addAll(scope.params());
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM alert a JOIN equipment e ON e.id = a.equipment_id"
                + " WHERE a.parent_alert_id = ? AND a.state <> 'DELETED'"
                + scope.sql(),
            Integer.class,
            params.toArray());
    return count == null ? 0 : count;
  }

  // ------------------------------------------------------------------ voisinage

  /**
   * Autres alertes déclenchées dans [from, to] sur l'équipement, son amont, son aval ou son site ;
   * les plus liées puis les plus proches dans le temps d'abord.
   */
  public List<NeighbourRow> neighbours(
      AlertFacts alert, Instant from, Instant to, Optional<Set<UUID>> visible, int limit) {
    var scope = inSites("e.site_id", visible);
    var params = graphParams(alert.equipmentId());
    params.add(alert.equipmentId());
    params.add(alert.id());
    params.add(Timestamp.from(from));
    params.add(Timestamp.from(to));
    params.add(alert.equipmentId());
    params.add(alert.siteId());
    params.addAll(scope.params());
    params.add(Timestamp.from(alert.openedAt()));
    params.add(limit);
    return jdbc.query(
        GRAPH
            + "SELECT a.id, a.equipment_id, e.name, a.type, a.severity, a.state, a.message,"
            + " a.triggered_at, CASE WHEN a.equipment_id = ? THEN 0"
            + " WHEN a.equipment_id IN (SELECT id FROM nearest_up) THEN 1"
            + " WHEN a.equipment_id IN (SELECT id FROM nearest_down) THEN 2 ELSE 3 END AS rel"
            + " FROM alert a JOIN equipment e ON e.id = a.equipment_id"
            + " WHERE a.id <> ? AND a.state <> 'DELETED' AND a.triggered_at BETWEEN ? AND ?"
            + " AND (a.equipment_id = ? OR a.equipment_id IN (SELECT id FROM nearest_up)"
            + " OR a.equipment_id IN (SELECT id FROM nearest_down) OR e.site_id = ?)"
            + scope.sql()
            + " ORDER BY rel, abs(extract(epoch FROM (a.triggered_at - ?))), a.id LIMIT ?",
        (rs, i) ->
            new NeighbourRow(
                uuid(rs, 1),
                uuid(rs, 2),
                rs.getString(3),
                rs.getString(4),
                rs.getString(5),
                rs.getString(6),
                rs.getString(7),
                instant(rs, 8),
                rs.getInt(9)),
        params.toArray());
  }

  /**
   * Alertes des équipements en amont, actives à l'ouverture ({@code resolved_at} postérieur ou nul)
   * et déclenchées au plus tard à {@code until} : alertes de collecte puis amont le plus éloigné
   * d'abord, comme la corrélation (ADR 0014).
   */
  public List<UpstreamAlertRow> upstreamAlerts(
      UUID equipmentId, Instant openedAt, Instant until, Optional<Set<UUID>> visible, int limit) {
    var scope = inSites("e.site_id", visible);
    var params = graphParams(equipmentId);
    params.add(Timestamp.from(until));
    params.add(Timestamp.from(openedAt));
    params.addAll(scope.params());
    params.add(limit);
    return jdbc.query(
        GRAPH
            + "SELECT a.id, a.equipment_id, e.name, n.depth, a.type, a.message, a.triggered_at"
            + " FROM nearest_up n JOIN alert a ON a.equipment_id = n.id"
            + " JOIN equipment e ON e.id = n.id"
            + " WHERE a.state <> 'DELETED' AND a.triggered_at <= ?"
            + " AND (a.resolved_at IS NULL OR a.resolved_at >= ?)"
            + scope.sql()
            + " ORDER BY CASE WHEN a.type = 'COLLECTION' THEN 0 ELSE 1 END, n.depth DESC,"
            + " a.triggered_at, a.id LIMIT ?",
        (rs, i) ->
            new UpstreamAlertRow(
                uuid(rs, 1),
                uuid(rs, 2),
                rs.getString(3),
                rs.getInt(4),
                rs.getString(5),
                rs.getString(6),
                instant(rs, 7)),
        params.toArray());
  }

  /**
   * Autres alertes de prévision, d'anomalie ou de collecte du même équipement, déclenchées au plus
   * tard à {@code until} et encore actives à {@code activeAfter}.
   */
  public List<OwnAlertRow> sameEquipmentAlerts(
      UUID equipmentId, UUID alertId, Instant until, Instant activeAfter, int limit) {
    return jdbc.query(
        "SELECT a.id, a.type, a.message, a.triggered_at, a.occurrence_key FROM alert a"
            + " WHERE a.equipment_id = ? AND a.id <> ? AND a.state <> 'DELETED'"
            + " AND a.type IN ('FORECAST', 'ANOMALY', 'COLLECTION') AND a.triggered_at <= ?"
            + " AND (a.resolved_at IS NULL OR a.resolved_at >= ?)"
            + " ORDER BY a.triggered_at DESC, a.id LIMIT ?",
        (rs, i) ->
            new OwnAlertRow(
                uuid(rs, 1), rs.getString(2), rs.getString(3), instant(rs, 4), rs.getString(5)),
        equipmentId,
        alertId,
        Timestamp.from(until),
        Timestamp.from(activeAfter),
        limit);
  }

  /** Événements réseau de l'équipement et de son amont visible reçus dans [from, to]. */
  public List<EventRow> events(
      UUID equipmentId, Instant from, Instant to, Optional<Set<UUID>> visible, int limit) {
    var scope = inSites("e.site_id", visible);
    var params = graphParams(equipmentId);
    params.add(equipmentId);
    params.add(equipmentId);
    params.add(Timestamp.from(from));
    params.add(Timestamp.from(to));
    params.addAll(scope.params());
    params.add(limit);
    return jdbc.query(
        GRAPH
            + "SELECT ne.id, ne.equipment_id, e.name, ne.source, ne.severity, ne.title,"
            + " ne.trap_oid, ne.received_at, ne.equipment_id <> ?"
            + " FROM network_event ne JOIN equipment e ON e.id = ne.equipment_id"
            + " WHERE (ne.equipment_id = ? OR ne.equipment_id IN (SELECT id FROM nearest_up))"
            + " AND ne.received_at BETWEEN ? AND ?"
            + scope.sql()
            + " ORDER BY ne.received_at DESC, ne.id LIMIT ?",
        (rs, i) ->
            new EventRow(
                uuid(rs, 1),
                uuid(rs, 2),
                rs.getString(3),
                rs.getString(4),
                rs.getString(5),
                rs.getString(6),
                rs.getString(7),
                instant(rs, 8),
                rs.getBoolean(9)),
        params.toArray());
  }

  /**
   * Entrées d'audit de la liste blanche {@code actions}, faites dans [from, to] sur l'équipement ou
   * son amont, sa règle, sa règle d'événement, ses sondes, ses maintenances ou son site. La colonne
   * {@code details_sanitized} n'est jamais lue. Le périmètre des équipements en amont est appliqué
   * par l'appelant (colonne {@code targetSiteId}).
   */
  public List<ChangeRow> changes(
      AlertFacts alert, Instant from, Instant to, Collection<String> actions, int limit) {
    if (actions.isEmpty()) {
      return List.of();
    }
    var params = graphParams(alert.equipmentId());
    params.add(alert.equipmentId());
    params.add(Timestamp.from(from));
    params.add(Timestamp.from(to));
    params.addAll(actions);
    params.add(alert.equipmentId());
    params.add(orNone(alert.ruleId()));
    params.add(orNone(alert.eventRuleId()));
    params.add(alert.equipmentId());
    params.add(alert.equipmentId());
    params.add(alert.siteId());
    params.add(alert.siteId());
    params.add(limit);
    return jdbc.query(
        GRAPH
            + "SELECT au.action, au.username, au.performed_at, au.target_type, au.target_id,"
            + " COALESCE(e.name, r.name, er.name, ms.name, me.name, msi.name, s.name),"
            + " e.site_id, (au.target_type = 'EQUIPMENT' AND au.target_id <> ?)"
            + " FROM audit_entry au"
            + " LEFT JOIN equipment e ON au.target_type = 'EQUIPMENT' AND e.id = au.target_id"
            + " LEFT JOIN alert_rule r ON au.target_type = 'ALERT_RULE' AND r.id = au.target_id"
            + " LEFT JOIN event_rule er ON au.target_type = 'EVENT_RULE' AND er.id = au.target_id"
            + " LEFT JOIN monitored_service ms ON au.target_type = 'PROBE'"
            + " AND ms.id = au.target_id"
            + " LEFT JOIN maintenance m ON au.target_type = 'MAINTENANCE' AND m.id = au.target_id"
            + " LEFT JOIN equipment me ON me.id = m.equipment_id"
            + " LEFT JOIN site msi ON msi.id = m.site_id"
            + " LEFT JOIN site s ON au.target_type = 'SITE' AND s.id = au.target_id"
            + " WHERE au.performed_at BETWEEN ? AND ? AND au.action IN ("
            + marks(actions.size())
            + ") AND ((au.target_type = 'EQUIPMENT' AND (au.target_id = ?"
            + " OR au.target_id IN (SELECT id FROM nearest_up)))"
            + " OR (au.target_type = 'ALERT_RULE' AND au.target_id = ?)"
            + " OR (au.target_type = 'EVENT_RULE' AND au.target_id = ?)"
            + " OR (au.target_type = 'PROBE' AND ms.equipment_id = ?)"
            + " OR (au.target_type = 'MAINTENANCE' AND (m.equipment_id = ? OR m.site_id = ?))"
            + " OR (au.target_type = 'SITE' AND au.target_id = ?))"
            + " ORDER BY au.performed_at DESC, au.id LIMIT ?",
        (rs, i) ->
            new ChangeRow(
                rs.getString(1),
                rs.getString(2),
                instant(rs, 3),
                rs.getString(4),
                uuid(rs, 5),
                rs.getString(6),
                uuid(rs, 7),
                rs.getBoolean(8)),
        params.toArray());
  }

  // ------------------------------------------------------------------ mesures

  /**
   * Dernières mesures (hors qualité BAD) jusqu'à {@code until}, de la plus ancienne à la plus
   * récente.
   */
  public List<MetricValue> lastValues(
      UUID equipmentId, String key, UUID serviceId, Instant until, int limit) {
    var params = new ArrayList<Object>(List.of(equipmentId, key));
    if (serviceId != null) {
      params.add(serviceId);
    }
    params.add(Timestamp.from(until));
    params.add(limit);
    var rows =
        new ArrayList<>(
            jdbc.query(
                "SELECT collected_at, metric_value, unit FROM metric"
                    + " WHERE equipment_id = ? AND metric_key = ? AND "
                    + serviceClause(serviceId)
                    + " AND quality <> 'BAD' AND collected_at <= ?"
                    + " ORDER BY collected_at DESC LIMIT ?",
                (rs, i) ->
                    new MetricValue(
                        instant(rs, 1), rs.getBigDecimal(2).doubleValue(), rs.getString(3)),
                params.toArray()));
    Collections.reverse(rows);
    return rows;
  }

  /**
   * Moyenne à l'heure {@code hourUtc} (UTC) sur [from, to[ : moyenne des moyennes journalières, et
   * nombre de jours qui ont des mesures.
   */
  public Reference reference(
      UUID equipmentId, String key, UUID serviceId, Instant from, Instant to, int hourUtc) {
    var params = new ArrayList<Object>(List.of(equipmentId, key));
    if (serviceId != null) {
      params.add(serviceId);
    }
    params.add(Timestamp.from(from));
    params.add(Timestamp.from(to));
    params.add(hourUtc);
    return jdbc.queryForObject(
        "SELECT avg(day_avg), count(*) FROM (SELECT date_trunc('day', collected_at AT TIME ZONE"
            + " 'UTC') AS d, avg(metric_value) AS day_avg FROM metric"
            + " WHERE equipment_id = ? AND metric_key = ? AND "
            + serviceClause(serviceId)
            + " AND quality <> 'BAD' AND collected_at >= ? AND collected_at < ?"
            + " AND extract(hour FROM collected_at AT TIME ZONE 'UTC') = ? GROUP BY d) t",
        (rs, i) -> new Reference(number(rs.getBigDecimal(1)), rs.getInt(2)),
        params.toArray());
  }

  // ------------------------------------------------------------------ métriques liées

  /** Moyennes d'un indicateur avant et autour de l'ouverture, avec le nombre de points. */
  public record MovedMetric(
      String key, String unit, Double before, Double during, int pointsBefore, int pointsDuring) {}

  /**
   * Indicateurs de l'équipement, avec leur moyenne « avant » (deux heures avant l'ouverture,
   * jusqu'à une demi-heure avant) et « pendant » (le quart d'heure qui précède l'ouverture et les
   * cinq minutes qui suivent). Les mesures de sonde sont écartées : plusieurs sondes d'un même
   * équipement partagent la même clé, leurs moyennes n'auraient pas de sens.
   */
  public List<MovedMetric> movedMetrics(UUID equipmentId, Instant opened, String excludedKey) {
    return jdbc.query(
        "SELECT metric_key, max(unit),"
            + " avg(metric_value) FILTER (WHERE collected_at < ?) AS before_value,"
            + " avg(metric_value) FILTER (WHERE collected_at >= ?) AS during_value,"
            + " count(*) FILTER (WHERE collected_at < ?) AS before_points,"
            + " count(*) FILTER (WHERE collected_at >= ?) AS during_points"
            + " FROM metric WHERE equipment_id = ? AND service_id IS NULL AND quality <> 'BAD'"
            + " AND collected_at >= ? AND collected_at <= ?"
            // Le type du paramètre doit être dit : PostgreSQL ne le devine pas dans « ? IS NULL ».
            + " AND (cast(? as text) IS NULL OR metric_key <> ?)"
            + " GROUP BY metric_key",
        (rs, i) ->
            new MovedMetric(
                rs.getString(1),
                rs.getString(2),
                number(rs.getBigDecimal(3)),
                number(rs.getBigDecimal(4)),
                rs.getInt(5),
                rs.getInt(6)),
        Timestamp.from(opened.minus(Duration.ofMinutes(15))),
        Timestamp.from(opened.minus(Duration.ofMinutes(15))),
        Timestamp.from(opened.minus(Duration.ofMinutes(15))),
        Timestamp.from(opened.minus(Duration.ofMinutes(15))),
        equipmentId,
        Timestamp.from(opened.minus(Duration.ofHours(2))),
        Timestamp.from(opened.plus(Duration.ofMinutes(5))),
        excludedKey,
        excludedKey);
  }

  // ------------------------------------------------------------------ incidents similaires

  /**
   * Ce qui rend deux alertes « similaires » : même règle (seuil ou événement), ou même type et même
   * préfixe de clé d'occurrence (indicateur de prévision ou d'anomalie), ou même type (collecte).
   */
  public record SimilarKey(String type, UUID ruleId, UUID eventRuleId, String keyPrefix) {}

  public SimilarStats similarStats(
      SimilarKey key, UUID alertId, UUID equipmentId, UUID siteId, Instant since) {
    var where = similarWhere(key, alertId, equipmentId, siteId, since);
    return jdbc.queryForObject(
        "SELECT count(*), count(a.resolved_at), percentile_cont(0.5) WITHIN GROUP (ORDER BY"
            + " extract(epoch FROM (a.resolved_at - a.triggered_at)) / 60.0),"
            + " avg(extract(epoch FROM (a.resolved_at - a.triggered_at)) / 60.0)"
            + " FROM alert a JOIN equipment e ON e.id = a.equipment_id WHERE "
            + where.sql(),
        (rs, i) ->
            new SimilarStats(
                rs.getInt(1),
                rs.getInt(2),
                rs.getObject(3) == null ? null : rs.getDouble(3),
                rs.getObject(4) == null ? null : rs.getDouble(4)),
        where.params().toArray());
  }

  public List<ResolutionRow> lastResolutions(
      SimilarKey key, UUID alertId, UUID equipmentId, UUID siteId, Instant since, int limit) {
    var where = similarWhere(key, alertId, equipmentId, siteId, since);
    var params = new ArrayList<>(where.params());
    params.add(limit);
    return jdbc.query(
        "SELECT a.id, a.triggered_at, a.resolved_at, a.resolution_type, ev.comment, u.username"
            + " FROM alert a JOIN equipment e ON e.id = a.equipment_id"
            + " LEFT JOIN LATERAL (SELECT x.comment, x.author_id FROM alert_event x"
            + " WHERE x.alert_id = a.id AND x.type = 'RESOLVED'"
            + " ORDER BY x.occurred_at DESC LIMIT 1) ev ON TRUE"
            + " LEFT JOIN app_user u ON u.id = ev.author_id WHERE "
            + where.sql()
            + " AND a.resolved_at IS NOT NULL ORDER BY a.resolved_at DESC, a.id LIMIT ?",
        (rs, i) ->
            new ResolutionRow(
                uuid(rs, 1),
                instant(rs, 2),
                instant(rs, 3),
                rs.getString(4),
                rs.getString(5),
                rs.getString(6)),
        params.toArray());
  }

  /** {@code equipmentId} non nul : même équipement ; sinon même site ({@code siteId}). */
  private static Clause similarWhere(
      SimilarKey key, UUID alertId, UUID equipmentId, UUID siteId, Instant since) {
    var sql = new StringBuilder("a.id <> ? AND a.state <> 'DELETED' AND a.triggered_at >= ?");
    var params = new ArrayList<Object>(List.of(alertId, Timestamp.from(since)));
    if (key.ruleId() != null) {
      sql.append(" AND a.rule_id = ?");
      params.add(key.ruleId());
    } else if (key.eventRuleId() != null) {
      sql.append(" AND a.event_rule_id = ?");
      params.add(key.eventRuleId());
    } else {
      sql.append(" AND a.type = ?");
      params.add(key.type());
      if (key.keyPrefix() != null) {
        sql.append(" AND starts_with(a.occurrence_key, ?)");
        params.add(key.keyPrefix());
      }
    }
    if (equipmentId != null) {
      sql.append(" AND a.equipment_id = ?");
      params.add(equipmentId);
    } else {
      sql.append(" AND e.site_id = ?");
      params.add(siteId);
    }
    return new Clause(sql.toString(), params);
  }

  // ------------------------------------------------------------------ outils

  private record Clause(String sql, List<Object> params) {}

  private record Counted<T>(T item, int total) {}

  private static <T> Page<T> page(List<Counted<T>> rows) {
    return new Page<>(
        rows.stream().map(Counted::item).toList(), rows.isEmpty() ? 0 : rows.get(0).total());
  }

  /** Filtre de périmètre : rien pour un compte sans restriction. */
  private static Clause inSites(String column, Optional<Set<UUID>> visible) {
    if (visible.isEmpty()) {
      return new Clause("", List.of());
    }
    if (visible.get().isEmpty()) {
      return new Clause(" AND FALSE", List.of());
    }
    return new Clause(
        " AND " + column + " IN (" + marks(visible.get().size()) + ")",
        new ArrayList<>(visible.get()));
  }

  private static String marks(int count) {
    return String.join(", ", Collections.nCopies(count, "?"));
  }

  private static ArrayList<Object> graphParams(UUID equipmentId) {
    return new ArrayList<>(List.of(equipmentId, equipmentId));
  }

  private static String serviceClause(UUID serviceId) {
    return serviceId == null ? "service_id IS NULL" : "service_id = ?";
  }

  private static UUID orNone(UUID id) {
    return id == null ? NONE : id;
  }

  private static UUID uuid(ResultSet rs, int column) throws SQLException {
    return rs.getObject(column, UUID.class);
  }

  private static Instant instant(ResultSet rs, int column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private static Double number(BigDecimal value) {
    return value == null ? null : value.doubleValue();
  }
}

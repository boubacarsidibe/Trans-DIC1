package sn.ept.transdic1.postmortem.repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import sn.ept.transdic1.postmortem.repository.PostMortemRows.AlertRow;
import sn.ept.transdic1.postmortem.repository.PostMortemRows.CauseRow;
import sn.ept.transdic1.postmortem.repository.PostMortemRows.ComponentRow;
import sn.ept.transdic1.postmortem.repository.PostMortemRows.EventRow;
import sn.ept.transdic1.postmortem.repository.PostMortemRows.IncidentRow;
import sn.ept.transdic1.postmortem.repository.PostMortemRows.KpiAlertRow;
import sn.ept.transdic1.postmortem.repository.PostMortemRows.MaintenanceRow;
import sn.ept.transdic1.postmortem.repository.PostMortemRows.NotificationRow;
import sn.ept.transdic1.postmortem.repository.PostMortemRows.OnCallRow;
import sn.ept.transdic1.postmortem.repository.PostMortemRows.RemediationRow;

/**
 * Lectures du post-mortem et des indicateurs de service : des requêtes SQL bornées (LIMIT), une par
 * rubrique, comme le diagnostic (ADR 0024). Le module ne dépend d'aucune classe des modules qu'il
 * lit : il ne fait que relire ce qui est déjà en base, après coup.
 *
 * <p>Les alertes d'un incident sont désignées par une sous-requête sur {@code incident_alert} : un
 * incident en compte au plus quelques centaines, et l'index de {@code incident_alert} la sert.
 */
@Repository
public class PostMortemRepository {
  private static final String INCIDENT_ALERTS =
      "SELECT alert_id FROM incident_alert WHERE incident_id = ?";

  private final JdbcTemplate jdbc;

  public PostMortemRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<IncidentRow> incident(UUID id) {
    return jdbc
        .query(
            "SELECT i.id, i.title, i.severity, i.state, i.opened_at, i.last_alert_at,"
                + " i.resolved_at, i.site_id, s.name FROM incident i"
                + " LEFT JOIN site s ON s.id = i.site_id WHERE i.id = ?",
            (rs, n) ->
                new IncidentRow(
                    rs.getObject(1, UUID.class),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getString(4),
                    instant(rs, 5),
                    instant(rs, 6),
                    instant(rs, 7),
                    rs.getObject(8, UUID.class),
                    rs.getString(9)),
            id)
        .stream()
        .findFirst();
  }

  public List<AlertRow> alerts(UUID incidentId) {
    return jdbc.query(
        "SELECT a.id, a.equipment_id, e.name, e.equipment_type, e.site_id, a.type, a.severity,"
            + " a.state, a.message, a.triggered_at, a.taken_at, a.resolved_at, a.resolution_type,"
            + " a.escalated_at, ia.reason, ia.announces, r.name"
            + " FROM incident_alert ia JOIN alert a ON a.id = ia.alert_id"
            + " JOIN equipment e ON e.id = a.equipment_id"
            + " LEFT JOIN alert_rule r ON r.id = a.rule_id"
            + " WHERE ia.incident_id = ? AND a.state <> 'DELETED'"
            + " ORDER BY a.triggered_at, a.id LIMIT 200",
        (rs, n) ->
            new AlertRow(
                rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class),
                rs.getString(3),
                rs.getString(4),
                rs.getObject(5, UUID.class),
                rs.getString(6),
                rs.getString(7),
                rs.getString(8),
                rs.getString(9),
                instant(rs, 10),
                instant(rs, 11),
                instant(rs, 12),
                rs.getString(13),
                instant(rs, 14),
                rs.getString(15),
                rs.getBoolean(16),
                rs.getString(17)),
        incidentId);
  }

  public List<EventRow> events(UUID incidentId) {
    return jdbc.query(
        "SELECT ev.alert_id, ev.type, ev.new_state,"
            // Escalade et renvoi (ADR 0059) : la phrase, les niveaux, les cases et les prévenus.
            + " CASE WHEN ev.type IN ('LEVEL_ESCALATED', 'SENT_BACK') THEN concat_ws(' — ',"
            + " ev.comment, 'de ' || coalesce('N' || ev.from_level, 'sans niveau') || ' vers '"
            + " || CASE WHEN ev.to_level IS NULL OR ev.to_level > 3 THEN 'administrateurs'"
            + " ELSE 'N' || ev.to_level END,"
            + " CASE WHEN ev.checklist IS NOT NULL THEN 'cases : ' || ev.checklist END,"
            + " CASE WHEN ev.recipients IS NOT NULL THEN 'prévenus : ' || ev.recipients END)"
            + " ELSE ev.comment END, ev.occurred_at, u.username"
            + " FROM alert_event ev LEFT JOIN app_user u ON u.id = ev.author_id"
            + " WHERE ev.alert_id IN ("
            + INCIDENT_ALERTS
            + ") ORDER BY ev.occurred_at, ev.id LIMIT 500",
        (rs, n) ->
            new EventRow(
                rs.getObject(1, UUID.class),
                rs.getString(2),
                rs.getString(3),
                rs.getString(4),
                instant(rs, 5),
                rs.getString(6)),
        incidentId);
  }

  public List<NotificationRow> notifications(UUID incidentId) {
    return jdbc.query(
        "SELECT n.alert_id, n.channel, n.state, n.kind, n.created_at, n.sent_at, u.username,"
            + " (SELECT count(*) FROM notification_attempt na WHERE na.notification_id = n.id)"
            + " FROM notification n JOIN app_user u ON u.id = n.user_id"
            + " WHERE n.alert_id IN ("
            + INCIDENT_ALERTS
            + ") ORDER BY n.created_at, n.id LIMIT 300",
        (rs, n) ->
            new NotificationRow(
                rs.getObject(1, UUID.class),
                rs.getString(2),
                rs.getString(3),
                rs.getString(4),
                instant(rs, 5),
                instant(rs, 6),
                rs.getString(7),
                rs.getInt(8)),
        incidentId);
  }

  public List<OnCallRow> onCallSteps(UUID incidentId) {
    return jdbc.query(
        "SELECT st.alert_id, st.level, st.outcome, st.occurred_at, u.username"
            + " FROM oncall_alert_step st LEFT JOIN app_user u ON u.id = st.user_id"
            + " WHERE st.alert_id IN ("
            + INCIDENT_ALERTS
            + ") ORDER BY st.occurred_at LIMIT 100",
        (rs, n) ->
            new OnCallRow(
                rs.getObject(1, UUID.class),
                rs.getString(2),
                rs.getString(3),
                instant(rs, 4),
                rs.getString(5)),
        incidentId);
  }

  public List<CauseRow> confirmedCauses(UUID incidentId) {
    return jdbc.query(
        "SELECT cf.alert_id, cf.source, cf.confirmed_at, cf.confirmed_by FROM cause_feedback cf"
            + " WHERE cf.chosen AND cf.alert_id IN ("
            + INCIDENT_ALERTS
            + ") ORDER BY cf.confirmed_at DESC LIMIT 20",
        (rs, n) ->
            new CauseRow(
                rs.getObject(1, UUID.class), rs.getString(2), instant(rs, 3), rs.getString(4)),
        incidentId);
  }

  public List<RemediationRow> remediations(UUID incidentId) {
    return jdbc.query(
        "SELECT j.alert_id, j.action_name, j.state, j.trigger_kind, j.created_at, j.finished_at,"
            + " j.exit_code, j.requested_by FROM remediation_job j WHERE j.alert_id IN ("
            + INCIDENT_ALERTS
            + ") ORDER BY j.created_at LIMIT 50",
        (rs, n) ->
            new RemediationRow(
                rs.getObject(1, UUID.class),
                rs.getString(2),
                rs.getString(3),
                rs.getString(4),
                instant(rs, 5),
                instant(rs, 6),
                (Integer) rs.getObject(7),
                rs.getString(8)),
        incidentId);
  }

  /** Maintenances qui recouvrent la fenêtre de l'incident, sur ses équipements ou leurs sites. */
  public List<MaintenanceRow> maintenances(
      Collection<UUID> equipments, Collection<UUID> sites, Instant from, Instant to) {
    if (equipments.isEmpty()) {
      return List.of();
    }
    return jdbc.query(
        con -> {
          var ps =
              con.prepareStatement(
                  "SELECT m.reason, m.start_at, m.end_at, coalesce(e.name, s.name)"
                      + " FROM maintenance m LEFT JOIN equipment e ON e.id = m.equipment_id"
                      + " LEFT JOIN site s ON s.id = m.site_id"
                      + " WHERE m.state <> 'CANCELLED' AND m.start_at < ? AND m.end_at > ?"
                      + " AND (m.equipment_id = ANY(?) OR m.site_id = ANY(?))"
                      + " ORDER BY m.start_at LIMIT 20");
          ps.setTimestamp(1, Timestamp.from(to));
          ps.setTimestamp(2, Timestamp.from(from));
          ps.setArray(3, con.createArrayOf("uuid", equipments.toArray()));
          ps.setArray(4, con.createArrayOf("uuid", sites.toArray()));
          return ps;
        },
        (rs, n) ->
            new MaintenanceRow(rs.getString(1), instant(rs, 2), instant(rs, 3), rs.getString(4)));
  }

  /** Composants de pages de statut actives reliés aux équipements touchés ou à leurs sondes. */
  public List<ComponentRow> statusComponents(Collection<UUID> equipments) {
    if (equipments.isEmpty()) {
      return List.of();
    }
    return jdbc.query(
        con -> {
          var ps =
              con.prepareStatement(
                  "SELECT c.display_name, p.title, p.visibility = 'PUBLIC'"
                      + " FROM status_component c JOIN status_page p ON p.id = c.page_id"
                      + " WHERE p.active AND ((c.source_type = 'EQUIPMENT'"
                      + " AND c.source_id = ANY(?)) OR (c.source_type = 'PROBE' AND c.source_id"
                      + " IN (SELECT id FROM monitored_service WHERE equipment_id = ANY(?))))"
                      + " ORDER BY p.title, c.position, c.display_name LIMIT 50");
          Array ids = con.createArrayOf("uuid", equipments.toArray());
          ps.setArray(1, ids);
          ps.setArray(2, ids);
          return ps;
        },
        (rs, n) -> new ComponentRow(rs.getString(1), rs.getString(2), rs.getBoolean(3)));
  }

  /** Incidents publics ouverts pendant la fenêtre (communication aux usagers). */
  public int publicCommunications(Instant from, Instant to) {
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM status_incident si JOIN status_page p ON p.id = si.page_id"
                + " WHERE p.visibility = 'PUBLIC' AND si.created_at >= ? AND si.created_at <= ?",
            Integer.class,
            Timestamp.from(from),
            Timestamp.from(to));
    return count == null ? 0 : count;
  }

  /** Autres incidents qui ont touché cet équipement dans la fenêtre donnée. */
  public int previousIncidents(UUID equipmentId, UUID excludedIncident, Instant from, Instant to) {
    Integer count =
        jdbc.queryForObject(
            "SELECT count(DISTINCT ia.incident_id) FROM incident_alert ia"
                + " JOIN alert a ON a.id = ia.alert_id"
                + " WHERE a.equipment_id = ? AND ia.incident_id <> ?"
                + " AND a.triggered_at >= ? AND a.triggered_at < ?",
            Integer.class,
            equipmentId,
            excludedIncident,
            Timestamp.from(from),
            Timestamp.from(to));
    return count == null ? 0 : count;
  }

  /**
   * Alertes racines (non rattachées à une autre) ouvertes dans la période, hors alertes supprimées.
   * Plafonnée : au-delà, l'écran le signale plutôt que de saturer la mémoire.
   */
  /** Changement de niveau d'une alerte (ADR 0059) : ligne de chronologie, dans l'ordre. */
  public record LevelMoveRow(UUID alertId, String type, Integer fromLevel, Instant at) {}

  /** Escalades et renvois des alertes données, dans l'ordre de la chronologie. */
  public List<LevelMoveRow> levelMoves(java.util.Collection<UUID> alertIds) {
    if (alertIds.isEmpty()) {
      return List.of();
    }
    return jdbc.execute(
        (ConnectionCallback<List<LevelMoveRow>>)
            con -> {
              var ps =
                  con.prepareStatement(
                      "SELECT alert_id, type, from_level, occurred_at FROM alert_event"
                          + " WHERE alert_id = ANY(?) AND type IN ('LEVEL_ESCALATED', 'SENT_BACK')"
                          + " ORDER BY alert_id, occurred_at, id");
              ps.setArray(1, con.createArrayOf("uuid", alertIds.toArray()));
              try (ResultSet rs = ps.executeQuery()) {
                var rows = new java.util.ArrayList<LevelMoveRow>();
                while (rs.next()) {
                  int level = rs.getInt(3);
                  rows.add(
                      new LevelMoveRow(
                          rs.getObject(1, UUID.class),
                          rs.getString(2),
                          rs.wasNull() ? null : level,
                          instant(rs, 4)));
                }
                return rows;
              }
            });
  }

  public List<KpiAlertRow> kpiAlerts(Instant from, Instant to, Set<UUID> sites, int limit) {
    String siteFilter = sites == null ? "" : " AND e.site_id = ANY(?)";
    return jdbc.execute(
        (ConnectionCallback<List<KpiAlertRow>>)
            con -> {
              var ps =
                  con.prepareStatement(
                      "SELECT a.id, a.severity, a.type, a.triggered_at, a.taken_at, a.resolved_at,"
                          + " a.resolution_type, e.site_id, s.name, e.id, e.name,"
                          + " (SELECT u.username FROM alert_event ev"
                          + " JOIN app_user u ON u.id = ev.author_id"
                          + " WHERE ev.alert_id = a.id AND ev.type = 'TAKEN'"
                          + " ORDER BY ev.occurred_at LIMIT 1)"
                          + " FROM alert a JOIN equipment e ON e.id = a.equipment_id"
                          + " LEFT JOIN site s ON s.id = e.site_id"
                          + " WHERE a.triggered_at >= ? AND a.triggered_at < ?"
                          + " AND a.state <> 'DELETED' AND a.parent_alert_id IS NULL"
                          + siteFilter
                          + " ORDER BY a.triggered_at LIMIT ?");
              int i = 1;
              ps.setTimestamp(i++, Timestamp.from(from));
              ps.setTimestamp(i++, Timestamp.from(to));
              if (sites != null) {
                ps.setArray(i++, con.createArrayOf("uuid", sites.toArray()));
              }
              ps.setInt(i, limit);
              try (ResultSet rs = ps.executeQuery()) {
                var rows = new java.util.ArrayList<KpiAlertRow>();
                while (rs.next()) {
                  rows.add(
                      new KpiAlertRow(
                          rs.getObject(1, UUID.class),
                          rs.getString(2),
                          rs.getString(3),
                          instant(rs, 4),
                          instant(rs, 5),
                          instant(rs, 6),
                          rs.getString(7),
                          rs.getObject(8, UUID.class),
                          rs.getString(9),
                          rs.getObject(10, UUID.class),
                          rs.getString(11),
                          rs.getString(12)));
                }
                return rows;
              } finally {
                ps.close();
              }
            });
  }

  private static Instant instant(ResultSet rs, int column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }
}

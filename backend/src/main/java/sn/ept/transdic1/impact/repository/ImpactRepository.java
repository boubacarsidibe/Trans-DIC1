package sn.ept.transdic1.impact.repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Lectures de l'analyse d'impact : une requête bornée par rubrique. L'aval est parcouru par une
 * requête récursive sur {@code equipment_link}, servie par son index sur {@code upstream_id}, avec
 * la même borne de profondeur que la topologie (une boucle saisie par erreur ne fait pas tourner la
 * requête sans fin).
 */
@Repository
public class ImpactRepository {
  private static final int MAX_DEPTH = 64;

  private final JdbcTemplate jdbc;

  public ImpactRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public record EquipmentRow(
      UUID id, String name, String type, String status, UUID siteId, String siteName) {}

  public record DownstreamRow(
      UUID id,
      String name,
      String type,
      String status,
      UUID siteId,
      String siteName,
      int depth,
      String via) {}

  public record LinkRow(UUID equipmentId, UUID upstreamId) {}

  public record ServiceRow(String name, String type, String equipmentName) {}

  public record ComponentRow(String name, String page, boolean publicPage) {}

  public record LearnedRow(UUID equipmentId, String name, UUID siteId, double confidence) {}

  public record MaintenanceRow(Instant startAt, Instant endAt, String reason) {}

  public Optional<EquipmentRow> equipment(UUID id) {
    return jdbc
        .query(
            "SELECT e.id, e.name, e.equipment_type, e.operational_status, e.site_id, s.name"
                + " FROM equipment e LEFT JOIN site s ON s.id = e.site_id WHERE e.id = ?",
            (rs, n) ->
                new EquipmentRow(
                    rs.getObject(1, UUID.class),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getObject(5, UUID.class),
                    rs.getString(6)),
            id)
        .stream()
        .findFirst();
  }

  /** Équipements en aval, à leur plus courte distance, avec l'équipement par lequel on y arrive. */
  public List<DownstreamRow> downstream(UUID id) {
    return jdbc.query(
        "WITH RECURSIVE down(id, parent, depth) AS ("
            + " SELECT equipment_id, upstream_id, 1 FROM equipment_link WHERE upstream_id = ?"
            + " UNION SELECT l.equipment_id, l.upstream_id, d.depth + 1 FROM equipment_link l"
            + " JOIN down d ON l.upstream_id = d.id WHERE d.depth < "
            + MAX_DEPTH
            + ") SELECT * FROM (SELECT DISTINCT ON (d.id) d.id, e.name, e.equipment_type,"
            + " e.operational_status, e.site_id, s.name AS site_name, d.depth, p.name AS via"
            + " FROM down d JOIN equipment e ON e.id = d.id"
            + " LEFT JOIN site s ON s.id = e.site_id LEFT JOIN equipment p ON p.id = d.parent"
            + " WHERE d.id <> ? ORDER BY d.id, d.depth, p.name) x"
            + " ORDER BY x.depth, x.name LIMIT 500",
        (rs, n) ->
            new DownstreamRow(
                rs.getObject(1, UUID.class),
                rs.getString(2),
                rs.getString(3),
                rs.getString(4),
                rs.getObject(5, UUID.class),
                rs.getString(6),
                rs.getInt(7),
                rs.getString(8)),
        id,
        id);
  }

  /** Tous les liens amont des équipements donnés : pour repérer ceux qui ont un second chemin. */
  public List<LinkRow> upstreamLinks(Collection<UUID> equipments) {
    if (equipments.isEmpty()) {
      return List.of();
    }
    return jdbc.query(
        con -> {
          var ps =
              con.prepareStatement(
                  "SELECT equipment_id, upstream_id FROM equipment_link"
                      + " WHERE equipment_id = ANY(?)");
          ps.setArray(1, con.createArrayOf("uuid", equipments.toArray()));
          return ps;
        },
        (rs, n) -> new LinkRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)));
  }

  public List<ServiceRow> services(Collection<UUID> equipments) {
    if (equipments.isEmpty()) {
      return List.of();
    }
    return jdbc.query(
        con -> {
          var ps =
              con.prepareStatement(
                  "SELECT ms.name, ms.type, e.name FROM monitored_service ms"
                      + " JOIN equipment e ON e.id = ms.equipment_id"
                      + " WHERE ms.active AND ms.equipment_id = ANY(?)"
                      + " ORDER BY e.name, ms.name LIMIT 200");
          ps.setArray(1, con.createArrayOf("uuid", equipments.toArray()));
          return ps;
        },
        (rs, n) -> new ServiceRow(rs.getString(1), rs.getString(2), rs.getString(3)));
  }

  /**
   * Composants de pages de statut actives qui reflètent un équipement touché, une de ses sondes, ou
   * le site de la cible (qui serait au moins dégradé).
   */
  public List<ComponentRow> statusComponents(Collection<UUID> equipments, UUID siteId) {
    if (equipments.isEmpty()) {
      return List.of();
    }
    return jdbc.query(
        con -> {
          var ps =
              con.prepareStatement(
                  "SELECT c.display_name, p.title, p.visibility = 'PUBLIC'"
                      + " FROM status_component c JOIN status_page p ON p.id = c.page_id"
                      + " WHERE p.active AND ((c.source_type = 'EQUIPMENT' AND c.source_id = ANY(?))"
                      + " OR (c.source_type = 'PROBE' AND c.source_id IN"
                      + " (SELECT id FROM monitored_service WHERE equipment_id = ANY(?)))"
                      + " OR (c.source_type = 'SITE' AND c.source_id = ?))"
                      + " ORDER BY p.visibility DESC, p.title, c.position LIMIT 100");
          Array ids = con.createArrayOf("uuid", equipments.toArray());
          ps.setArray(1, ids);
          ps.setArray(2, ids);
          ps.setObject(3, siteId);
          return ps;
        },
        (rs, n) -> new ComponentRow(rs.getString(1), rs.getString(2), rs.getBoolean(3)));
  }

  public int activeAlerts(Collection<UUID> equipments) {
    if (equipments.isEmpty()) {
      return 0;
    }
    Integer count =
        jdbc.query(
            con -> {
              var ps =
                  con.prepareStatement(
                      "SELECT count(*) FROM alert WHERE state IN ('OPEN', 'IN_PROGRESS')"
                          + " AND equipment_id = ANY(?)");
              ps.setArray(1, con.createArrayOf("uuid", equipments.toArray()));
              return ps;
            },
            rs -> rs.next() ? rs.getInt(1) : 0);
    return count == null ? 0 : count;
  }

  /** Liens appris (règles d'association) acceptés par un administrateur, qui touchent la cible. */
  public List<LearnedRow> acceptedLearnedLinks(UUID id) {
    return jdbc.query(
        "SELECT o.id, o.name, o.site_id, lc.confidence FROM learned_correlation lc"
            + " JOIN equipment o ON o.id = CASE WHEN lc.first_equipment_id = ?"
            + " THEN lc.second_equipment_id ELSE lc.first_equipment_id END"
            + " WHERE lc.state = 'ACCEPTED' AND (lc.first_equipment_id = ? OR"
            + " lc.second_equipment_id = ?) ORDER BY lc.confidence DESC LIMIT 20",
        (rs, n) ->
            new LearnedRow(
                rs.getObject(1, UUID.class),
                rs.getString(2),
                rs.getObject(3, UUID.class),
                rs.getDouble(4)),
        id,
        id,
        id);
  }

  /** Personne de garde à ce niveau pour le site, maintenant (remplacements d'abord). */
  public Optional<String> onCall(UUID siteId, String level, Instant now) {
    return jdbc
        .query(
            "SELECT u.username FROM oncall_shift sh JOIN oncall_schedule sc ON sc.id = sh.schedule_id"
                + " JOIN app_user u ON u.id = sh.user_id"
                + " WHERE sc.active AND sh.level = ? AND sh.starts_at <= ? AND sh.ends_at > ?"
                + " AND (sc.all_sites OR EXISTS (SELECT 1 FROM oncall_schedule_site ss"
                + " WHERE ss.schedule_id = sc.id AND ss.site_id = ?))"
                + " ORDER BY sh.override DESC, sh.starts_at DESC LIMIT 1",
            (rs, n) -> rs.getString(1),
            level,
            Timestamp.from(now),
            Timestamp.from(now),
            siteId)
        .stream()
        .findFirst();
  }

  public List<MaintenanceRow> plannedMaintenances(UUID equipmentId, UUID siteId, Instant now) {
    return jdbc.query(
        "SELECT start_at, end_at, reason FROM maintenance"
            + " WHERE state IN ('PLANNED', 'IN_PROGRESS') AND end_at > ?"
            + " AND (equipment_id = ? OR site_id = ?) ORDER BY start_at LIMIT 10",
        (rs, n) -> new MaintenanceRow(instant(rs, 1), instant(rs, 2), rs.getString(3)),
        Timestamp.from(now),
        equipmentId,
        siteId);
  }

  private static Instant instant(ResultSet rs, int column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }
}

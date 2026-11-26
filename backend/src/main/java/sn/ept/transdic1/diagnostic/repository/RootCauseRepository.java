package sn.ept.transdic1.diagnostic.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Lectures de la recherche de cause racine (ADR 0056), par requêtes bornées : le diagnostic reste
 * en lecture seule et ne passe par l'API d'aucun autre module. « Au même moment » veut dire : une
 * alerte dont la durée de vie recoupe la fenêtre [instant − marge, instant + marge].
 */
@Repository
public class RootCauseRepository {
  /** Point de départ : l'alerte étudiée (ou l'alerte racine d'un incident). */
  public record Subject(
      UUID alertId,
      UUID equipmentId,
      UUID serviceId,
      UUID siteId,
      String type,
      String message,
      Instant openedAt) {}

  /** Une alerte qui recoupe la fenêtre. */
  public record AlertFact(
      UUID id, String type, String message, String family, Instant triggeredAt) {}

  /** Un équipement et ce qu'il en est au moment étudié. */
  public record EquipmentFact(
      UUID id,
      String name,
      String type,
      UUID siteId,
      AlertFact collection,
      List<AlertFact> resources,
      Double power,
      List<UUID> upstreams) {}

  /** Un service (sonde ou service suivi) et ce qu'il en est au moment étudié. */
  public record ServiceFact(
      UUID id,
      String name,
      String type,
      UUID equipmentId,
      UUID siteId,
      List<AlertFact> alerts,
      Double lastUp,
      List<UUID> dependsOn) {}

  private static final String OVERLAP =
      " AND a.state <> 'DELETED' AND a.triggered_at <= ? AND (a.resolved_at IS NULL OR a.resolved_at >= ?)";
  private static final String FAMILY =
      "CASE WHEN lower(coalesce(r.metric_type, a.occurrence_key)) LIKE '%disk%' THEN 'disk'"
          + " WHEN lower(coalesce(r.metric_type, a.occurrence_key)) LIKE '%swap%' THEN 'memory'"
          + " WHEN lower(coalesce(r.metric_type, a.occurrence_key)) LIKE '%memory%' THEN 'memory'"
          + " WHEN lower(coalesce(r.metric_type, a.occurrence_key)) LIKE '%cpu%' THEN 'cpu'"
          + " ELSE NULL END";

  private final JdbcTemplate jdbc;

  public RootCauseRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<Subject> alert(UUID alertId) {
    return jdbc
        .query(
            "SELECT a.id, a.equipment_id, a.service_id, e.site_id, a.type, a.message,"
                + " COALESCE((SELECT max(ev.occurred_at) FROM alert_event ev"
                + " WHERE ev.alert_id = a.id AND ev.type = 'OPENED'), a.triggered_at)"
                + " FROM alert a JOIN equipment e ON e.id = a.equipment_id"
                + " WHERE a.id = ? AND a.state <> 'DELETED'",
            (rs, i) ->
                new Subject(
                    rs.getObject(1, UUID.class),
                    rs.getObject(2, UUID.class),
                    rs.getObject(3, UUID.class),
                    rs.getObject(4, UUID.class),
                    rs.getString(5),
                    rs.getString(6),
                    rs.getTimestamp(7).toInstant()),
            alertId)
        .stream()
        .findFirst();
  }

  /**
   * Alerte de départ d'un incident : celle qui n'est rattachée à aucune autre, la plus ancienne. Le
   * site de l'incident sert au contrôle du périmètre.
   */
  public Optional<UUID> incidentRootAlert(UUID incidentId) {
    return jdbc
        .queryForList(
            "SELECT a.id FROM incident_alert ia JOIN alert a ON a.id = ia.alert_id"
                + " WHERE ia.incident_id = ? AND a.state <> 'DELETED'"
                + " ORDER BY (a.parent_alert_id IS NULL) DESC, a.triggered_at, a.id LIMIT 1",
            UUID.class,
            incidentId)
        .stream()
        .findFirst();
  }

  public Optional<UUID> incidentSite(UUID incidentId) {
    return jdbc
        .query(
            "SELECT site_id FROM incident WHERE id = ?",
            (rs, i) -> Optional.ofNullable(rs.getObject(1, UUID.class)),
            incidentId)
        .stream()
        .findFirst()
        .orElse(null);
  }

  public boolean incidentExists(UUID incidentId) {
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM incident WHERE id = ?", Integer.class, incidentId);
    return count != null && count > 0;
  }

  public Optional<EquipmentFact> equipment(UUID id, Instant from, Instant to) {
    var base =
        jdbc.query(
            "SELECT id, name, equipment_type, site_id FROM equipment WHERE id = ?",
            (rs, i) ->
                new Object[] {
                  rs.getObject(1, UUID.class),
                  rs.getString(2),
                  rs.getString(3),
                  rs.getObject(4, UUID.class)
                },
            id);
    if (base.isEmpty()) {
      return Optional.empty();
    }
    Object[] row = base.get(0);
    List<AlertFact> collection =
        alerts(
            "a.equipment_id = ? AND a.type = 'COLLECTION'"
                + OVERLAP
                + " ORDER BY a.triggered_at LIMIT 1",
            id,
            Timestamp.from(to),
            Timestamp.from(from));
    List<AlertFact> resources =
        alerts(
                "a.equipment_id = ? AND a.service_id IS NULL AND a.type <> 'COLLECTION'"
                    + OVERLAP
                    + " ORDER BY a.triggered_at LIMIT 20",
                id,
                Timestamp.from(to),
                Timestamp.from(from))
            .stream()
            .filter(a -> a.family() != null)
            .toList();
    Double power =
        jdbc
            .query(
                "SELECT metric_value FROM metric WHERE equipment_id = ? AND metric_key = 'hv.power'"
                    + " AND collected_at BETWEEN ? AND ? ORDER BY collected_at DESC LIMIT 1",
                (rs, i) -> rs.getDouble(1),
                id,
                Timestamp.from(from),
                Timestamp.from(to))
            .stream()
            .findFirst()
            .orElse(null);
    List<UUID> upstreams =
        jdbc.queryForList(
            "SELECT upstream_id FROM equipment_link WHERE equipment_id = ? ORDER BY upstream_id LIMIT 20",
            UUID.class,
            id);
    return Optional.of(
        new EquipmentFact(
            (UUID) row[0],
            (String) row[1],
            (String) row[2],
            (UUID) row[3],
            collection.isEmpty() ? null : collection.get(0),
            resources,
            power,
            upstreams));
  }

  public Optional<ServiceFact> service(UUID id, Instant from, Instant to) {
    var base =
        jdbc.query(
            "SELECT s.id, s.name, s.type, s.equipment_id, e.site_id FROM monitored_service s"
                + " JOIN equipment e ON e.id = s.equipment_id WHERE s.id = ?",
            (rs, i) ->
                new Object[] {
                  rs.getObject(1, UUID.class),
                  rs.getString(2),
                  rs.getString(3),
                  rs.getObject(4, UUID.class),
                  rs.getObject(5, UUID.class)
                },
            id);
    if (base.isEmpty()) {
      return Optional.empty();
    }
    Object[] row = base.get(0);
    UUID equipmentId = (UUID) row[3];
    String type = (String) row[2];
    List<AlertFact> alerts =
        alerts(
            "a.service_id = ?" + OVERLAP + " ORDER BY a.triggered_at LIMIT 5",
            id,
            Timestamp.from(to),
            Timestamp.from(from));
    // Une sonde a son propre indicateur (probe.up) ; un service suivi par l'agent, service.up[nom].
    List<Double> last =
        "AGENT_SERVICE".equals(type)
            ? jdbc.query(
                "SELECT metric_value FROM metric WHERE equipment_id = ? AND metric_key = ?"
                    + " AND collected_at BETWEEN ? AND ? ORDER BY collected_at DESC LIMIT 1",
                (rs, i) -> rs.getDouble(1),
                equipmentId,
                "service.up[" + row[1] + "]",
                Timestamp.from(from),
                Timestamp.from(to))
            : jdbc.query(
                "SELECT metric_value FROM metric WHERE service_id = ? AND metric_key = 'probe.up'"
                    + " AND collected_at BETWEEN ? AND ? ORDER BY collected_at DESC LIMIT 1",
                (rs, i) -> rs.getDouble(1),
                id,
                Timestamp.from(from),
                Timestamp.from(to));
    List<UUID> dependsOn =
        jdbc.queryForList(
            "SELECT depends_on_id FROM service_dependency WHERE service_id = ? ORDER BY depends_on_id LIMIT 20",
            UUID.class,
            id);
    return Optional.of(
        new ServiceFact(
            (UUID) row[0],
            (String) row[1],
            type,
            equipmentId,
            (UUID) row[4],
            alerts,
            last.isEmpty() ? null : last.get(0),
            dependsOn));
  }

  private List<AlertFact> alerts(String where, Object... args) {
    return jdbc.query(
        "SELECT a.id, a.type, a.message, "
            + FAMILY
            + ", a.triggered_at FROM alert a LEFT JOIN alert_rule r ON r.id = a.rule_id WHERE "
            + where,
        (rs, i) ->
            new AlertFact(
                rs.getObject(1, UUID.class),
                rs.getString(2),
                rs.getString(3),
                rs.getString(4),
                rs.getTimestamp(5).toInstant()),
        args);
  }
}

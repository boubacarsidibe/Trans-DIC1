package sn.ept.transdic1.inventory.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import sn.ept.transdic1.audit.service.AuditService;
import sn.ept.transdic1.inventory.dto.TopologyDtos.Graph;
import sn.ept.transdic1.inventory.dto.TopologyDtos.Impact;
import sn.ept.transdic1.inventory.dto.TopologyDtos.Link;
import sn.ept.transdic1.inventory.dto.TopologyDtos.Node;

/**
 * Topologie du réseau : qui dépend de qui. Les liens forment un graphe orienté sans cycle (un
 * équipement ne peut pas dépendre, même indirectement, de ses propres dépendants). L'impact d'une
 * panne se calcule par une requête récursive : tous les équipements en aval.
 */
@Service
public class TopologyService {
  private static final String NODE_SELECT =
      "SELECT e.id, e.name, e.inventory_code, e.equipment_type, e.operational_status, s.id, s.name"
          + " FROM equipment e"
          + " JOIN site s ON s.id = e.site_id";

  /** Équipements en aval d'un équipement (récursif), avec leur distance. */
  private static final String DOWNSTREAM =
      "WITH RECURSIVE down(id, depth) AS ("
          + " SELECT equipment_id, 1 FROM equipment_link WHERE upstream_id = ?"
          + " UNION SELECT l.equipment_id, d.depth + 1 FROM equipment_link l"
          + " JOIN down d ON l.upstream_id = d.id WHERE d.depth < 64)";

  private final JdbcTemplate jdbc;
  private final AuditService audit;

  public TopologyService(JdbcTemplate jdbc, AuditService audit) {
    this.jdbc = jdbc;
    this.audit = audit;
  }

  @Transactional(readOnly = true)
  public Graph graph() {
    List<Node> nodes = jdbc.query(NODE_SELECT + " ORDER BY s.name, e.name", (rs, i) -> node(rs));
    List<Link> links =
        jdbc.query(
            "SELECT id, equipment_id, upstream_id FROM equipment_link ORDER BY created_at",
            (rs, i) ->
                new Link(
                    rs.getObject(1, UUID.class),
                    rs.getObject(2, UUID.class),
                    rs.getObject(3, UUID.class)));
    return new Graph(nodes, links);
  }

  @Transactional
  public Link addLink(UUID equipmentId, UUID upstreamId) {
    if (equipmentId.equals(upstreamId)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Un équipement ne peut pas dépendre de lui-même");
    }
    requireEquipment(equipmentId);
    requireEquipment(upstreamId);
    // L'amont ne doit pas déjà dépendre (même indirectement) de l'équipement : ce serait un cycle.
    Integer loops =
        jdbc.queryForObject(
            DOWNSTREAM + " SELECT count(*) FROM down WHERE id = ?",
            Integer.class,
            equipmentId,
            upstreamId);
    if (loops != null && loops > 0) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Ce lien créerait une boucle dans la topologie");
    }
    UUID id = UUID.randomUUID();
    try {
      jdbc.update(
          "INSERT INTO equipment_link(id, equipment_id, upstream_id) VALUES (?, ?, ?)",
          id,
          equipmentId,
          upstreamId);
    } catch (DuplicateKeyException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Ce lien existe déjà");
    }
    audit.record("TOPOLOGY_LINK_ADDED", "EQUIPMENT", equipmentId, "amont=" + upstreamId);
    return new Link(id, equipmentId, upstreamId);
  }

  @Transactional
  public void removeLink(UUID id) {
    var link =
        jdbc
            .query(
                "SELECT equipment_id, upstream_id FROM equipment_link WHERE id = ?",
                (rs, i) -> new UUID[] {rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)},
                id)
            .stream()
            .findFirst()
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Lien introuvable"));
    jdbc.update("DELETE FROM equipment_link WHERE id = ?", id);
    audit.record("TOPOLOGY_LINK_REMOVED", "EQUIPMENT", link[0], "amont=" + link[1]);
  }

  @Transactional(readOnly = true)
  public Impact impact(UUID equipmentId) {
    requireEquipment(equipmentId);
    List<Node> dependents =
        jdbc.query(
            DOWNSTREAM
                + ", nearest AS (SELECT id, min(depth) AS depth FROM down GROUP BY id) "
                + NODE_SELECT
                + " JOIN nearest n ON n.id = e.id ORDER BY n.depth, e.name",
            (rs, i) -> node(rs),
            equipmentId);
    return new Impact(equipmentId, dependents);
  }

  private void requireEquipment(UUID id) {
    Integer found =
        jdbc.queryForObject("SELECT count(*) FROM equipment WHERE id = ?", Integer.class, id);
    if (found == null || found == 0) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Équipement inconnu");
    }
  }

  private static Node node(ResultSet rs) throws SQLException {
    return new Node(
        rs.getObject(1, UUID.class),
        rs.getString(2),
        rs.getString(3),
        rs.getString(4),
        rs.getString(5),
        rs.getObject(6, UUID.class),
        rs.getString(7));
  }
}

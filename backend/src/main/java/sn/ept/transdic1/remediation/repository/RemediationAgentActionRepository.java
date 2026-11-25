package sn.ept.transdic1.remediation.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Actions annoncées par les agents : un nom et une description, jamais la commande. La liste d'un
 * agent est remplacée à chaque annonce : une action retirée de sa configuration disparaît.
 */
@Repository
public class RemediationAgentActionRepository {
  /**
   * Une action annoncée par un agent qui ne s'est pas manifesté depuis plus longtemps est ignorée.
   */
  private static final String FRESH = "interval '1 day'";

  private final JdbcTemplate jdbc;

  public RemediationAgentActionRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public record AgentAction(String name, String description) {}

  public record AvailableAction(String name, String description, int equipmentCount) {}

  public void replace(UUID agentId, List<AgentAction> actions, Instant now) {
    jdbc.update("DELETE FROM remediation_agent_action WHERE agent_id = ?", agentId);
    for (AgentAction action : actions) {
      jdbc.update(
          "INSERT INTO remediation_agent_action(agent_id, action_name, description, seen_at)"
              + " VALUES (?, ?, ?, ?) ON CONFLICT (agent_id, action_name) DO UPDATE"
              + " SET description = excluded.description, seen_at = excluded.seen_at",
          agentId,
          action.name(),
          action.description(),
          Timestamp.from(now));
    }
  }

  /** Actions de l'agent actif d'un équipement. */
  public List<AgentAction> ofEquipment(UUID equipmentId) {
    return jdbc.query(
        "SELECT ra.action_name, ra.description FROM remediation_agent_action ra"
            + " JOIN collection_agent ca ON ca.id = ra.agent_id"
            + " WHERE ca.equipment_id = ? AND ca.status = 'ACTIVE'"
            + " AND ra.seen_at > now() - "
            + FRESH
            + " ORDER BY ra.action_name",
        (rs, n) -> new AgentAction(rs.getString(1), rs.getString(2)),
        equipmentId);
  }

  public boolean offers(UUID agentId, String actionName) {
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM remediation_agent_action WHERE agent_id = ? AND action_name = ?",
            Integer.class,
            agentId,
            actionName);
    return count != null && count > 0;
  }

  /**
   * Toutes les actions annoncées par des agents actifs, avec le nombre d'équipements qui les
   * offrent.
   */
  public List<AvailableAction> available() {
    return jdbc.query(
        "SELECT ra.action_name, max(ra.description), count(DISTINCT ca.equipment_id)"
            + " FROM remediation_agent_action ra JOIN collection_agent ca ON ca.id = ra.agent_id"
            + " WHERE ca.status = 'ACTIVE' AND ra.seen_at > now() - "
            + FRESH
            + " GROUP BY ra.action_name ORDER BY ra.action_name LIMIT 200",
        (rs, n) -> new AvailableAction(rs.getString(1), rs.getString(2), rs.getInt(3)));
  }
}

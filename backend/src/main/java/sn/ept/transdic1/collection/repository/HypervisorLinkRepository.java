package sn.ept.transdic1.collection.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Liens VM → hôte posés par un connecteur d'hyperviseur (ADR 0055), dans la table de topologie
 * existante ({@code equipment_link}, ADR 0014 et 0039) : c'est elle qui range les alertes de
 * collecte d'une VM sous celle de son hôte. Seuls les liens vers les hôtes du connecteur sont
 * touchés ; un lien saisi à la main vers un autre équipement (un commutateur) n'est jamais retiré.
 */
@Repository
public class HypervisorLinkRepository {
  private final JdbcTemplate jdbc;

  public HypervisorLinkRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Parmi les candidats, ceux dont l'équipement dépend déjà. */
  public List<UUID> upstreamsAmong(UUID equipmentId, Collection<UUID> candidates) {
    if (candidates.isEmpty()) {
      return List.of();
    }
    String marks = String.join(",", candidates.stream().map(c -> "?").toList());
    Object[] args = new Object[candidates.size() + 1];
    args[0] = equipmentId;
    int i = 1;
    for (UUID candidate : candidates) {
      args[i++] = candidate;
    }
    return jdbc.queryForList(
        "SELECT upstream_id FROM equipment_link WHERE equipment_id = ? AND upstream_id IN ("
            + marks
            + ")",
        UUID.class,
        args);
  }

  /** Pose le lien s'il n'existe pas ; retourne vrai s'il a été créé. */
  public boolean link(UUID equipmentId, UUID upstreamId) {
    return jdbc.update(
            "INSERT INTO equipment_link(id, equipment_id, upstream_id) VALUES (?, ?, ?)"
                + " ON CONFLICT (equipment_id, upstream_id) DO NOTHING",
            UUID.randomUUID(),
            equipmentId,
            upstreamId)
        == 1;
  }

  /** Retire le lien vers un ancien hôte (la VM a migré). */
  public int unlink(UUID equipmentId, UUID upstreamId) {
    return jdbc.update(
        "DELETE FROM equipment_link WHERE equipment_id = ? AND upstream_id = ?",
        equipmentId,
        upstreamId);
  }
}

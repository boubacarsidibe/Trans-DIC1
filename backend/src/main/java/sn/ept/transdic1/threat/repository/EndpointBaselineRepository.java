package sn.ept.transdic1.threat.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import sn.ept.transdic1.threat.domain.EndpointBaselineItem;
import sn.ept.transdic1.threat.domain.EndpointBaselineItem.Category;

/**
 * Profil et ligne de base de sécurité des postes (ADR 0058), en JDBC : plusieurs centaines
 * d'éléments par rapport, écrits par lots, sans intérêt à devenir des entités.
 */
@Repository
public class EndpointBaselineRepository {
  private final JdbcTemplate jdbc;

  public EndpointBaselineRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Instant de la ligne de base de l'équipement, vide s'il n'a jamais envoyé de rapport. */
  public Optional<Instant> baselineAt(UUID equipmentId) {
    return jdbc
        .query(
            "SELECT baseline_at FROM endpoint_profile WHERE equipment_id = ?",
            (rs, i) -> rs.getTimestamp(1).toInstant(),
            equipmentId)
        .stream()
        .findFirst();
  }

  public void saveProfile(UUID equipmentId, String os, boolean partial, Instant at) {
    jdbc.update(
        "INSERT INTO endpoint_profile (equipment_id, os, baseline_at, last_report_at, reports, partial)"
            + " VALUES (?, ?, ?, ?, 1, ?)"
            + " ON CONFLICT (equipment_id) DO UPDATE SET os = EXCLUDED.os,"
            + " last_report_at = EXCLUDED.last_report_at, reports = endpoint_profile.reports + 1,"
            + " partial = EXCLUDED.partial",
        equipmentId,
        os,
        Timestamp.from(at),
        Timestamp.from(at),
        partial);
  }

  public List<EndpointBaselineItem> baseline(UUID equipmentId) {
    return jdbc.query(
        "SELECT category, item_key, fingerprint FROM endpoint_baseline WHERE equipment_id = ?",
        (rs, i) ->
            new EndpointBaselineItem(
                Category.valueOf(rs.getString(1)), rs.getString(2), rs.getString(3)),
        equipmentId);
  }

  /** Ajoute les éléments nouveaux et met à jour l'empreinte des autres ; rien n'est oublié. */
  public void upsert(UUID equipmentId, List<EndpointBaselineItem> items, Instant at) {
    Timestamp now = Timestamp.from(at);
    jdbc.batchUpdate(
        "INSERT INTO endpoint_baseline (equipment_id, category, item_key, fingerprint, first_seen_at,"
            + " last_seen_at) VALUES (?, ?, ?, ?, ?, ?)"
            + " ON CONFLICT (equipment_id, category, item_key) DO UPDATE"
            + " SET fingerprint = EXCLUDED.fingerprint, last_seen_at = EXCLUDED.last_seen_at",
        items,
        200,
        (ps, item) -> {
          ps.setObject(1, equipmentId);
          ps.setString(2, item.category().name());
          ps.setString(3, truncate(item.key(), 400));
          ps.setString(4, item.fingerprint());
          ps.setTimestamp(5, now);
          ps.setTimestamp(6, now);
        });
  }

  /** Synthèse par poste pour l'écran : système, dernier rapport, constats ouverts. */
  public record ProfileRow(
      UUID equipmentId,
      String equipmentName,
      UUID siteId,
      String os,
      Instant baselineAt,
      Instant lastReportAt,
      int reports,
      boolean partial,
      int openFindings) {}

  public List<ProfileRow> profiles() {
    return jdbc.query(
        "SELECT p.equipment_id, e.name, e.site_id, p.os, p.baseline_at, p.last_report_at, p.reports,"
            + " p.partial, (SELECT count(*) FROM endpoint_finding f WHERE f.equipment_id ="
            + " p.equipment_id AND f.state = 'OPEN')"
            + " FROM endpoint_profile p JOIN equipment e ON e.id = p.equipment_id"
            + " ORDER BY e.name",
        (rs, i) ->
            new ProfileRow(
                rs.getObject(1, UUID.class),
                rs.getString(2),
                rs.getObject(3, UUID.class),
                rs.getString(4),
                rs.getTimestamp(5).toInstant(),
                rs.getTimestamp(6).toInstant(),
                rs.getInt(7),
                rs.getBoolean(8),
                rs.getInt(9)));
  }

  private static String truncate(String text, int max) {
    return text.length() <= max ? text : text.substring(0, max);
  }
}

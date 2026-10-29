package sn.ept.transdic1.audit.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.audit.dto.AuditEntryResponse;

/**
 * Consultation du journal d'audit : lecture seule, filtrée par période, auteur, action et cible.
 * Les valeurs filtrées sont toujours des paramètres liés ; le tri est fixe (plus récent d'abord).
 */
@Service
public class AuditQueryService {
  /** Plafond d'une exportation, pour ne jamais charger tout le journal en mémoire. */
  public static final int EXPORT_LIMIT = 10_000;

  private static final String SELECT =
      "SELECT id, username, action, target_type, target_id, correlation_id, details_sanitized,"
          + " performed_at FROM audit_entry";

  private final JdbcTemplate jdbc;

  public AuditQueryService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Filtres facultatifs ; un champ vide ne filtre pas. */
  public record Filter(
      Instant from, Instant to, String username, String action, String targetType, UUID targetId) {}

  @Transactional(readOnly = true)
  public Page<AuditEntryResponse> search(Filter filter, Pageable pageable) {
    List<Object> args = new ArrayList<>();
    String where = where(filter, args);
    Long total =
        jdbc.queryForObject("SELECT count(*) FROM audit_entry" + where, Long.class, args.toArray());
    List<Object> pageArgs = new ArrayList<>(args);
    pageArgs.add(pageable.getPageSize());
    pageArgs.add(pageable.getOffset());
    List<AuditEntryResponse> rows =
        jdbc.query(
            SELECT + where + " ORDER BY performed_at DESC, id LIMIT ? OFFSET ?",
            (rs, i) -> map(rs),
            pageArgs.toArray());
    return new PageImpl<>(rows, pageable, total == null ? 0 : total);
  }

  /** Les {@link #EXPORT_LIMIT} entrées les plus récentes correspondant aux filtres. */
  @Transactional(readOnly = true)
  public List<AuditEntryResponse> export(Filter filter) {
    List<Object> args = new ArrayList<>();
    String where = where(filter, args);
    args.add(EXPORT_LIMIT);
    return jdbc.query(
        SELECT + where + " ORDER BY performed_at DESC, id LIMIT ?",
        (rs, i) -> map(rs),
        args.toArray());
  }

  /** Actions déjà journalisées, pour alimenter le filtre de l'écran. */
  @Transactional(readOnly = true)
  public List<String> actions() {
    return jdbc.queryForList(
        "SELECT DISTINCT action FROM audit_entry ORDER BY action", String.class);
  }

  private static String where(Filter f, List<Object> args) {
    List<String> clauses = new ArrayList<>();
    if (f.from() != null) {
      clauses.add("performed_at >= ?");
      args.add(Timestamp.from(f.from()));
    }
    if (f.to() != null) {
      clauses.add("performed_at < ?");
      args.add(Timestamp.from(f.to()));
    }
    if (f.username() != null && !f.username().isBlank()) {
      clauses.add("lower(username) = lower(?)");
      args.add(f.username().trim());
    }
    if (f.action() != null && !f.action().isBlank()) {
      clauses.add("action = ?");
      args.add(f.action().trim());
    }
    if (f.targetType() != null && !f.targetType().isBlank()) {
      clauses.add("target_type = ?");
      args.add(f.targetType().trim());
    }
    if (f.targetId() != null) {
      clauses.add("target_id = ?");
      args.add(f.targetId());
    }
    return clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses);
  }

  private static AuditEntryResponse map(ResultSet rs) throws SQLException {
    return new AuditEntryResponse(
        rs.getObject(1, UUID.class),
        rs.getString(2),
        rs.getString(3),
        rs.getString(4),
        rs.getObject(5, UUID.class),
        rs.getString(6),
        rs.getString(7),
        rs.getTimestamp(8).toInstant());
  }
}

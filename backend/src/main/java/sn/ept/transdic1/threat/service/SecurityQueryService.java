package sn.ept.transdic1.threat.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.common.scope.SiteScope;

/**
 * Lecture du journal de sécurité pour l'écran : ce qui s'est passé récemment, d'où cela vient et ce
 * qui est visé. Tout est filtré par le périmètre de sites du compte connecté, comme le reste de la
 * plateforme.
 */
@Service
public class SecurityQueryService {
  /** Lignes rendues au plus par liste : l'écran montre un aperçu, pas tout l'historique. */
  private static final int LIMIT = 50;

  private final NamedParameterJdbcTemplate jdbc;
  private final SiteScope scope;
  private final Clock clock;
  private final Duration defaultWindow;

  public SecurityQueryService(
      NamedParameterJdbcTemplate jdbc,
      SiteScope scope,
      Clock clock,
      @Value("${threat.overview-hours:24}") long overviewHours) {
    this.jdbc = jdbc;
    this.scope = scope;
    this.clock = clock;
    this.defaultWindow = Duration.ofHours(Math.max(1, overviewHours));
  }

  /** Tout ce que l'écran Sécurité affiche, en une seule lecture cohérente. */
  public record SecurityOverview(
      Instant generatedAt,
      int hours,
      Counts counts,
      List<Attacker> topAttackers,
      List<TargetedEquipment> targets,
      List<RecentEvent> recent) {}

  /** Chiffres clés de la période. */
  public record Counts(int attacks, int authFailures, int webErrors, int distinctSources) {}

  /** Une adresse qui frappe, avec ce qu'elle a tenté. */
  public record Attacker(
      String address, int attacks, int authFailures, int webErrors, Instant lastSeen) {}

  /** Un équipement visé. */
  public record TargetedEquipment(UUID equipmentId, String name, int events, Instant lastSeen) {}

  /** Une ligne du journal, telle que l'écran la montre. */
  public record RecentEvent(
      UUID id,
      UUID equipmentId,
      String equipment,
      Instant occurredAt,
      String kind,
      String sourceAddress,
      String username,
      String method,
      String request,
      Integer status,
      String families,
      int score) {}

  @Transactional(readOnly = true)
  public SecurityOverview overview(Integer hours) {
    Instant now = clock.instant();
    Duration window =
        hours == null ? defaultWindow : Duration.ofHours(Math.max(1, Math.min(hours, 168)));
    var params = new MapSqlParameterSource();
    params.addValue("since", Timestamp.from(now.minus(window)));
    params.addValue("limit", LIMIT);

    String sites = "";
    Optional<Set<UUID>> restriction = scope.restriction();
    if (restriction.isPresent()) {
      if (restriction.get().isEmpty()) {
        return new SecurityOverview(
            now, (int) window.toHours(), new Counts(0, 0, 0, 0), List.of(), List.of(), List.of());
      }
      params.addValue("sites", restriction.get());
      sites = " AND e.site_id IN (:sites)";
    }
    String from =
        " FROM security_event s JOIN equipment e ON e.id = s.equipment_id"
            + " WHERE s.occurred_at >= :since"
            + sites;

    Counts counts =
        jdbc.queryForObject(
            "SELECT count(*) FILTER (WHERE s.kind = 'WEB_ATTACK'),"
                + " count(*) FILTER (WHERE s.kind = 'AUTH_FAILURE'),"
                + " count(*) FILTER (WHERE s.kind = 'WEB_ERROR'),"
                + " count(DISTINCT s.source_address)"
                + from,
            params,
            (rs, i) -> new Counts(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4)));

    List<Attacker> attackers =
        jdbc.query(
            "SELECT s.source_address,"
                + " count(*) FILTER (WHERE s.kind = 'WEB_ATTACK'),"
                + " count(*) FILTER (WHERE s.kind = 'AUTH_FAILURE'),"
                + " count(*) FILTER (WHERE s.kind = 'WEB_ERROR'),"
                + " max(s.occurred_at)"
                + from
                + " AND s.source_address IS NOT NULL"
                + " GROUP BY s.source_address"
                + " ORDER BY count(*) FILTER (WHERE s.kind = 'WEB_ATTACK') DESC, count(*) DESC"
                + " LIMIT :limit",
            params,
            (rs, i) ->
                new Attacker(
                    rs.getString(1),
                    rs.getInt(2),
                    rs.getInt(3),
                    rs.getInt(4),
                    rs.getTimestamp(5).toInstant()));

    List<TargetedEquipment> targets =
        jdbc.query(
            "SELECT e.id, e.name, count(*), max(s.occurred_at)"
                + from
                + " GROUP BY e.id, e.name ORDER BY count(*) DESC LIMIT :limit",
            params,
            (rs, i) ->
                new TargetedEquipment(
                    rs.getObject(1, UUID.class),
                    rs.getString(2),
                    rs.getInt(3),
                    rs.getTimestamp(4).toInstant()));

    List<RecentEvent> recent =
        jdbc.query(
            "SELECT s.id, e.id, e.name, s.occurred_at, s.kind, s.source_address, s.username,"
                + " s.method, s.request, s.status, s.families, s.score"
                + from
                + " ORDER BY s.occurred_at DESC LIMIT :limit",
            params,
            (rs, i) ->
                new RecentEvent(
                    rs.getObject(1, UUID.class),
                    rs.getObject(2, UUID.class),
                    rs.getString(3),
                    rs.getTimestamp(4).toInstant(),
                    rs.getString(5),
                    rs.getString(6),
                    rs.getString(7),
                    rs.getString(8),
                    rs.getString(9),
                    (Integer) rs.getObject(10),
                    rs.getString(11),
                    rs.getInt(12)));

    return new SecurityOverview(
        now,
        (int) window.toHours(),
        counts == null ? new Counts(0, 0, 0, 0) : counts,
        attackers,
        targets,
        new ArrayList<>(recent));
  }
}

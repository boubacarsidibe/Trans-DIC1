package sn.ept.transdic1.flow.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
 * Lecture des flux réseau pour l'écran Trafic (ADR 0032) : combien d'octets, qui parle le plus, à
 * qui, et pour quels services. Tout est filtré par le périmètre de sites du compte connecté, comme
 * le reste de la plateforme.
 *
 * <p>Les totaux viennent de {@code flow_bucket}, qui reste juste même quand le détail a été
 * plafonné ; les classements viennent de {@code flow_aggregate}, qui ne contient que les plus
 * grosses conversations de chaque minute. L'écran le dit quand les deux ne concordent pas.
 */
@Service
public class FlowQueryService {
  /** Lignes rendues au plus par classement. */
  private static final int LIMIT = 20;

  private final NamedParameterJdbcTemplate jdbc;
  private final SiteScope scope;
  private final Clock clock;
  private final Duration defaultWindow;

  public FlowQueryService(
      NamedParameterJdbcTemplate jdbc,
      SiteScope scope,
      Clock clock,
      @Value("${flow.overview-hours:3}") long overviewHours) {
    this.jdbc = jdbc;
    this.scope = scope;
    this.clock = clock;
    this.defaultWindow = Duration.ofHours(Math.max(1, overviewHours));
  }

  /** Tout ce que l'écran Trafic affiche, en une seule lecture cohérente. */
  public record TrafficOverview(
      Instant generatedAt,
      int hours,
      Totals totals,
      List<TalkingEquipment> equipments,
      List<Conversation> conversations,
      List<Talker> talkers,
      List<Service> services,
      List<Point> timeline) {}

  /** Chiffres clés de la période. */
  public record Totals(
      long bytes, long packets, long flows, int equipments, boolean detailTruncated) {}

  /** Un équipement qui exporte des flux, et ce qu'il a vu passer. */
  public record TalkingEquipment(UUID equipmentId, String name, long bytes, long packets) {}

  /** Une conversation entre deux adresses. */
  public record Conversation(
      String sourceAddress,
      String destinationAddress,
      int protocol,
      int destinationPort,
      String service,
      long bytes,
      long packets) {}

  /** Une adresse, vue comme émettrice. */
  public record Talker(String address, long bytes, long packets, int peers) {}

  /** Un service (port et protocole) et ce qu'il pèse. */
  public record Service(int protocol, int port, String label, long bytes, long packets) {}

  /** Un point de la courbe : une minute et ce qui y est passé. */
  public record Point(Instant minute, long bytes, long packets) {}

  @Transactional(readOnly = true)
  public TrafficOverview overview(Integer hours, UUID equipmentId) {
    Instant now = clock.instant();
    Duration window =
        hours == null ? defaultWindow : Duration.ofHours(Math.max(1, Math.min(hours, 168)));
    var params = new MapSqlParameterSource();
    params.addValue("since", Timestamp.from(now.minus(window)));
    params.addValue("limit", LIMIT);

    String filters = "";
    Optional<Set<UUID>> restriction = scope.restriction();
    if (restriction.isPresent()) {
      if (restriction.get().isEmpty()) {
        return empty(now, window);
      }
      params.addValue("sites", restriction.get());
      filters += " AND e.site_id IN (:sites)";
    }
    if (equipmentId != null) {
      params.addValue("equipment", equipmentId);
      filters += " AND e.id = :equipment";
    }

    String fromBuckets =
        " FROM flow_bucket b JOIN equipment e ON e.id = b.equipment_id"
            + " WHERE b.bucket_start >= :since"
            + filters;
    String fromAggregates =
        " FROM flow_aggregate a JOIN equipment e ON e.id = a.equipment_id"
            + " WHERE a.bucket_start >= :since"
            + filters;

    Totals totals =
        jdbc.queryForObject(
            "SELECT coalesce(sum(b.bytes), 0), coalesce(sum(b.packets), 0),"
                + " coalesce(sum(b.flows), 0), count(DISTINCT b.equipment_id),"
                + " coalesce(bool_or(b.truncated), false)"
                + fromBuckets,
            params,
            (rs, i) ->
                new Totals(
                    rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getInt(4), rs.getBoolean(5)));

    List<TalkingEquipment> equipments =
        jdbc.query(
            "SELECT e.id, e.name, sum(b.bytes), sum(b.packets)"
                + fromBuckets
                + " GROUP BY e.id, e.name ORDER BY sum(b.bytes) DESC LIMIT :limit",
            params,
            (rs, i) ->
                new TalkingEquipment(
                    rs.getObject(1, UUID.class), rs.getString(2), rs.getLong(3), rs.getLong(4)));

    List<Conversation> conversations =
        jdbc.query(
            "SELECT a.source_address, a.destination_address, a.protocol, a.destination_port,"
                + " sum(a.bytes), sum(a.packets)"
                + fromAggregates
                + " GROUP BY a.source_address, a.destination_address, a.protocol,"
                + " a.destination_port ORDER BY sum(a.bytes) DESC LIMIT :limit",
            params,
            (rs, i) ->
                new Conversation(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getInt(3),
                    rs.getInt(4),
                    PortNames.label(rs.getInt(3), rs.getInt(4)),
                    rs.getLong(5),
                    rs.getLong(6)));

    List<Talker> talkers =
        jdbc.query(
            "SELECT a.source_address, sum(a.bytes), sum(a.packets),"
                + " count(DISTINCT a.destination_address)"
                + fromAggregates
                + " GROUP BY a.source_address ORDER BY sum(a.bytes) DESC LIMIT :limit",
            params,
            (rs, i) -> new Talker(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getInt(4)));

    List<Service> services =
        jdbc.query(
            "SELECT a.protocol, a.destination_port, sum(a.bytes), sum(a.packets)"
                + fromAggregates
                + " GROUP BY a.protocol, a.destination_port ORDER BY sum(a.bytes) DESC"
                + " LIMIT :limit",
            params,
            (rs, i) ->
                new Service(
                    rs.getInt(1),
                    rs.getInt(2),
                    PortNames.label(rs.getInt(1), rs.getInt(2)),
                    rs.getLong(3),
                    rs.getLong(4)));

    List<Point> timeline =
        jdbc.query(
            "SELECT b.bucket_start, sum(b.bytes), sum(b.packets)"
                + fromBuckets
                + " GROUP BY b.bucket_start ORDER BY b.bucket_start",
            params,
            (rs, i) -> new Point(rs.getTimestamp(1).toInstant(), rs.getLong(2), rs.getLong(3)));

    return new TrafficOverview(
        now,
        (int) window.toHours(),
        totals == null ? new Totals(0, 0, 0, 0, false) : totals,
        equipments,
        conversations,
        talkers,
        services,
        timeline);
  }

  private TrafficOverview empty(Instant now, Duration window) {
    return new TrafficOverview(
        now,
        (int) window.toHours(),
        new Totals(0, 0, 0, 0, false),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of());
  }
}

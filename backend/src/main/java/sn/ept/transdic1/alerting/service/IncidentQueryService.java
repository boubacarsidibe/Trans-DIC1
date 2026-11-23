package sn.ept.transdic1.alerting.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import sn.ept.transdic1.alerting.domain.AlertState;
import sn.ept.transdic1.alerting.domain.AlertType;
import sn.ept.transdic1.alerting.domain.IncidentState;
import sn.ept.transdic1.alerting.domain.Severity;
import sn.ept.transdic1.alerting.dto.IncidentDtos.IncidentAlertResponse;
import sn.ept.transdic1.alerting.dto.IncidentDtos.IncidentDetailResponse;
import sn.ept.transdic1.alerting.dto.IncidentDtos.IncidentResponse;
import sn.ept.transdic1.common.scope.SiteScope;

/**
 * Lecture des incidents (M10, ADR 0039) pour l'écran et l'API. Lecture seule : il n'y a pas de
 * cycle de vie propre à un incident, ce sont ses alertes qui portent la prise en charge et la
 * résolution.
 *
 * <p>Le périmètre d'accès (ADR 0018) s'applique au <b>site d'origine</b> de l'incident. Un incident
 * qui a franchi un lien inter-sites reste donc invisible, dans cette liste, à un opérateur limité à
 * l'autre site — il voit malgré tout ses propres alertes sur l'écran des alertes. Le détail ne
 * montre que les alertes des sites visibles, pour la même raison.
 */
@Service
public class IncidentQueryService {

  /** Au-delà, la liste des noms d'équipements ne tient plus sur une ligne d'écran. */
  private static final int NAMES_SHOWN = 5;

  private final JdbcTemplate jdbc;
  private final SiteScope scope;

  public IncidentQueryService(JdbcTemplate jdbc, SiteScope scope) {
    this.jdbc = jdbc;
    this.scope = scope;
  }

  /** Liste paginée, les plus récents d'abord, filtrable par état. */
  @Transactional(readOnly = true)
  public Page<IncidentResponse> list(IncidentState state, Pageable pageable) {
    var conditions = new ArrayList<String>();
    var arguments = new ArrayList<Object>();
    if (state != null) {
      conditions.add("i.state = ?");
      arguments.add(state.name());
    }
    Optional<Set<UUID>> restriction = scope.restriction();
    if (restriction.isPresent()) {
      Set<UUID> sites = restriction.get();
      if (sites.isEmpty()) {
        return new PageImpl<>(List.of(), pageable, 0);
      }
      conditions.add("i.site_id IN (" + placeholders(sites.size()) + ")");
      arguments.addAll(sites);
    }
    String where = conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
    Integer total =
        jdbc.queryForObject(
            "SELECT count(*) FROM incident i" + where, Integer.class, arguments.toArray());
    if (total == null || total == 0) {
      return new PageImpl<>(List.of(), pageable, 0);
    }
    var paged = new ArrayList<>(arguments);
    paged.add(pageable.getPageSize());
    paged.add(pageable.getOffset());
    List<IncidentResponse> rows =
        jdbc.query(
            "SELECT i.id, i.title, i.severity, i.state, i.site_id, s.name, i.opened_at,"
                + " i.last_alert_at, i.resolved_at FROM incident i"
                + " LEFT JOIN site s ON s.id = i.site_id"
                + where
                + " ORDER BY i.opened_at DESC LIMIT ? OFFSET ?",
            (rs, index) -> summary(rs),
            paged.toArray());
    return new PageImpl<>(rows, pageable, total);
  }

  /** Un incident et ses alertes, dans l'ordre où elles l'ont rejoint. */
  @Transactional(readOnly = true)
  public IncidentDetailResponse get(UUID id) {
    List<IncidentResponse> found =
        jdbc.query(
            "SELECT i.id, i.title, i.severity, i.state, i.site_id, s.name, i.opened_at,"
                + " i.last_alert_at, i.resolved_at FROM incident i"
                + " LEFT JOIN site s ON s.id = i.site_id WHERE i.id = ?",
            (rs, index) -> summary(rs),
            id);
    if (found.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Incident introuvable");
    }
    IncidentResponse incident = found.get(0);
    scope.requireVisible(incident.siteId(), "Incident introuvable");
    return new IncidentDetailResponse(incident, alerts(id));
  }

  /** Alertes de l'incident visibles par l'utilisateur connecté. */
  private List<IncidentAlertResponse> alerts(UUID incidentId) {
    List<IncidentAlertResponse> rows =
        jdbc
            .query(
                "SELECT a.id, a.type, a.severity, a.state, a.equipment_id, e.name, a.message,"
                    + " a.triggered_at, ia.joined_at, ia.reason, ia.announces, e.site_id"
                    + " FROM incident_alert ia JOIN alert a ON a.id = ia.alert_id"
                    + " JOIN equipment e ON e.id = a.equipment_id"
                    + " WHERE ia.incident_id = ? ORDER BY ia.joined_at",
                (rs, index) ->
                    new Visible(
                        new IncidentAlertResponse(
                            rs.getObject(1, UUID.class),
                            AlertType.valueOf(rs.getString(2)),
                            Severity.valueOf(rs.getString(3)),
                            AlertState.valueOf(rs.getString(4)),
                            rs.getObject(5, UUID.class),
                            rs.getString(6),
                            rs.getString(7),
                            rs.getTimestamp(8).toInstant(),
                            rs.getTimestamp(9).toInstant(),
                            rs.getString(10),
                            rs.getBoolean(11)),
                        rs.getObject(12, UUID.class)),
                incidentId)
            .stream()
            .filter(visible -> scope.allows(visible.siteId()))
            .map(Visible::alert)
            .toList();
    return rows;
  }

  /** Une alerte et le site qui décide si on a le droit de la voir. */
  private record Visible(IncidentAlertResponse alert, UUID siteId) {}

  private IncidentResponse summary(ResultSet rs) throws SQLException {
    UUID id = rs.getObject(1, UUID.class);
    List<String> names =
        jdbc.queryForList(
            "SELECT DISTINCT e.name FROM incident_alert ia JOIN alert a ON a.id = ia.alert_id"
                + " JOIN equipment e ON e.id = a.equipment_id WHERE ia.incident_id = ?"
                + " ORDER BY e.name",
            String.class,
            id);
    Integer alertCount =
        jdbc.queryForObject(
            "SELECT count(*) FROM incident_alert WHERE incident_id = ?", Integer.class, id);
    return new IncidentResponse(
        id,
        rs.getString(2),
        Severity.valueOf(rs.getString(3)),
        IncidentState.valueOf(rs.getString(4)),
        rs.getObject(5, UUID.class),
        rs.getString(6),
        rs.getTimestamp(7).toInstant(),
        rs.getTimestamp(8).toInstant(),
        rs.getTimestamp(9) == null ? null : rs.getTimestamp(9).toInstant(),
        alertCount == null ? 0 : alertCount,
        names.size(),
        names.stream().limit(NAMES_SHOWN).toList());
  }

  private static String placeholders(int count) {
    return String.join(", ", java.util.Collections.nCopies(count, "?"));
  }
}

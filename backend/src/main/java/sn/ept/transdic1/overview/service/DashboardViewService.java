package sn.ept.transdic1.overview.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import sn.ept.transdic1.overview.dto.DashboardViewDtos.ViewRequest;
import sn.ept.transdic1.overview.dto.DashboardViewDtos.ViewResponse;

/**
 * Vues personnelles de tableau de bord. Une vue n'est visible et modifiable que par son
 * propriétaire ; les données affichées par ses widgets viennent des API habituelles, donc des
 * droits de l'utilisateur qui la regarde.
 */
@Service
public class DashboardViewService {
  /** Widgets proposés par l'écran ; une valeur inconnue est refusée. */
  public static final Set<String> WIDGETS =
      Set.of(
          "summary", "statuses", "severities", "open-alerts", "down-equipments", "latest-metric");

  private static final int MAX_VIEWS = 20;

  private final JdbcTemplate jdbc;
  private final Clock clock;

  public DashboardViewService(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public List<ViewResponse> list(String username) {
    return jdbc.query(
        "SELECT id, name, widgets, position, updated_at FROM dashboard_view WHERE owner_id = ?"
            + " ORDER BY position, name",
        (rs, i) ->
            new ViewResponse(
                rs.getObject(1, UUID.class),
                rs.getString(2),
                Arrays.asList(rs.getString(3).split(",")),
                rs.getInt(4),
                rs.getTimestamp(5).toInstant()),
        owner(username));
  }

  @Transactional
  public ViewResponse create(String username, ViewRequest request) {
    UUID owner = owner(username);
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM dashboard_view WHERE owner_id = ?", Integer.class, owner);
    if (count != null && count >= MAX_VIEWS) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Vingt vues au plus par utilisateur");
    }
    UUID id = UUID.randomUUID();
    try {
      jdbc.update(
          "INSERT INTO dashboard_view(id, owner_id, name, widgets, position, created_at,"
              + " updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
          id,
          owner,
          request.name().trim(),
          widgets(request.widgets()),
          count == null ? 0 : count,
          Timestamp.from(clock.instant()),
          Timestamp.from(clock.instant()));
    } catch (DuplicateKeyException e) {
      throw duplicate();
    }
    return find(owner, id);
  }

  @Transactional
  public ViewResponse update(String username, UUID id, ViewRequest request) {
    UUID owner = owner(username);
    int rows;
    try {
      rows =
          jdbc.update(
              "UPDATE dashboard_view SET name = ?, widgets = ?, updated_at = ? WHERE id = ? AND"
                  + " owner_id = ?",
              request.name().trim(),
              widgets(request.widgets()),
              Timestamp.from(clock.instant()),
              id,
              owner);
    } catch (DuplicateKeyException e) {
      throw duplicate();
    }
    if (rows == 0) {
      throw notFound();
    }
    return find(owner, id);
  }

  @Transactional
  public void delete(String username, UUID id) {
    if (jdbc.update("DELETE FROM dashboard_view WHERE id = ? AND owner_id = ?", id, owner(username))
        == 0) {
      throw notFound();
    }
  }

  private ViewResponse find(UUID owner, UUID id) {
    return jdbc
        .query(
            "SELECT id, name, widgets, position, updated_at FROM dashboard_view WHERE id = ? AND"
                + " owner_id = ?",
            (rs, i) ->
                new ViewResponse(
                    rs.getObject(1, UUID.class),
                    rs.getString(2),
                    Arrays.asList(rs.getString(3).split(",")),
                    rs.getInt(4),
                    rs.getTimestamp(5).toInstant()),
            id,
            owner)
        .stream()
        .findFirst()
        .orElseThrow(DashboardViewService::notFound);
  }

  private static String widgets(List<String> widgets) {
    for (String widget : widgets) {
      if (!WIDGETS.contains(widget)) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Widget inconnu : " + widget);
      }
    }
    if (Set.copyOf(widgets).size() != widgets.size()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Un widget ne figure qu'une fois");
    }
    return String.join(",", widgets);
  }

  private UUID owner(String username) {
    return jdbc
        .queryForList("SELECT id FROM app_user WHERE username = ?", UUID.class, username)
        .stream()
        .findFirst()
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Utilisateur inconnu"));
  }

  private static ResponseStatusException notFound() {
    // Une vue d'un autre utilisateur est traitée comme inexistante : on ne révèle pas qu'elle
    // existe.
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "Vue introuvable");
  }

  private static ResponseStatusException duplicate() {
    return new ResponseStatusException(HttpStatus.CONFLICT, "Vous avez déjà une vue de ce nom");
  }
}

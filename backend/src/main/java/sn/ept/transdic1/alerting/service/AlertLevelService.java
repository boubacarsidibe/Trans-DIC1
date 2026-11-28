package sn.ept.transdic1.alerting.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import sn.ept.transdic1.alerting.domain.Alert;
import sn.ept.transdic1.alerting.domain.AlertActivityEvent;
import sn.ept.transdic1.alerting.domain.AlertChangedEvent;
import sn.ept.transdic1.alerting.domain.AlertEvent;
import sn.ept.transdic1.alerting.domain.AlertEventType;
import sn.ept.transdic1.alerting.domain.AlertLevelChangedEvent;
import sn.ept.transdic1.alerting.domain.AlertState;
import sn.ept.transdic1.alerting.domain.Severity;
import sn.ept.transdic1.alerting.dto.AlertLevelDtos;
import sn.ept.transdic1.alerting.dto.AlertLevelDtos.Candidate;
import sn.ept.transdic1.alerting.dto.AlertLevelDtos.EscalationRequest;
import sn.ept.transdic1.alerting.dto.AlertLevelDtos.EscalationTarget;
import sn.ept.transdic1.alerting.dto.AlertLevelDtos.SendBackRequest;
import sn.ept.transdic1.alerting.dto.AlertResponse;
import sn.ept.transdic1.alerting.repository.AlertAssignmentRepository;
import sn.ept.transdic1.alerting.repository.AlertEventRepository;
import sn.ept.transdic1.alerting.repository.AlertRepository;
import sn.ept.transdic1.audit.service.AuditService;
import sn.ept.transdic1.common.scope.SiteScope;
import sn.ept.transdic1.security.domain.AppUser;
import sn.ept.transdic1.security.domain.Role;
import sn.ept.transdic1.security.repository.UserRepository;

/**
 * Niveaux de support et journal d'intervention (ADR 0059).
 *
 * <p>Un opérateur NOC peut avoir un niveau (N1, N2, N3) ; une alerte a un niveau courant. Un
 * opérateur prend les alertes de son niveau ou d'un niveau inférieur ; un opérateur sans niveau et
 * l'administrateur prennent tout. L'escalade est manuelle : elle vise le niveau supérieur suivant
 * qui compte au moins un opérateur actif voyant le site (un niveau vide est sauté), sinon les
 * administrateurs. Le renvoi descend de la même façon. Chaque note, escalade et renvoi est une
 * ligne datée et signée de la chronologie, et une entrée d'audit ; le choix des destinataires et
 * l'envoi appartiennent à l'astreinte, qui écoute {@link AlertLevelChangedEvent}.
 */
@Service
public class AlertLevelService {
  private static final int MAX_LEVEL = 3;

  /** Niveau d'une alerte escaladée aux administrateurs : aucun opérateur ne la prend plus. */
  public static final int ADMIN_LEVEL = 4;

  private final AlertRepository alerts;
  private final AlertEventRepository events;
  private final AlertAssignmentRepository assignments;
  private final UserRepository users;
  private final AuditService audit;
  private final SiteScope scope;
  private final Clock clock;
  private final ApplicationEventPublisher publisher;
  private final boolean criticalToTop;

  public AlertLevelService(
      AlertRepository alerts,
      AlertEventRepository events,
      AlertAssignmentRepository assignments,
      UserRepository users,
      AuditService audit,
      SiteScope scope,
      Clock clock,
      ApplicationEventPublisher publisher,
      @Value("${alerting.levels.critical-to-top:false}") boolean criticalToTop) {
    this.criticalToTop = criticalToTop;
    this.alerts = alerts;
    this.events = events;
    this.assignments = assignments;
    this.users = users;
    this.audit = audit;
    this.scope = scope;
    this.clock = clock;
    this.publisher = publisher;
  }

  // ------------------------------------------------------------------ règles

  /** Prendre une alerte : son niveau, ou un niveau inférieur ; sans niveau, tout. */
  public static boolean canTake(AppUser user, Alert alert) {
    if (user.getRole() == Role.ADMIN) {
      return true;
    }
    if (user.getRole() != Role.NOC_OPERATOR) {
      return false;
    }
    if (Objects.equals(alert.getCurrentLevel(), ADMIN_LEVEL)) {
      return false;
    }
    Integer level = user.getSupportLevel();
    return level == null || alert.getCurrentLevel() == null || alert.getCurrentLevel() <= level;
  }

  /** Refus clair quand l'alerte est d'un niveau trop élevé pour l'opérateur. */
  public static void requireCanTake(AppUser user, Alert alert) {
    if (!canTake(user, alert)) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN,
          alert.getCurrentLevel() == ADMIN_LEVEL
              ? "Cette alerte a été escaladée aux administrateurs."
              : "Cette alerte est au niveau N"
                  + alert.getCurrentLevel()
                  + " : elle est réservée à ce niveau et aux niveaux supérieurs.");
    }
  }

  /** Niveaux comptant au moins un opérateur actif qui voit le site de l'alerte. */
  TreeSet<Integer> occupiedLevels(Alert alert) {
    TreeSet<Integer> levels = new TreeSet<>();
    for (AppUser operator : users.findByRoleAndActiveTrue(Role.NOC_OPERATOR)) {
      if (operator.getSupportLevel() != null && scope.allowsUser(operator.getId(), siteOf(alert))) {
        levels.add(operator.getSupportLevel());
      }
    }
    return levels;
  }

  /** Plus haut niveau occupé pour cette alerte, ou nul s'il n'y en a aucun. */
  @Transactional(readOnly = true)
  public Integer highestOccupiedLevel(UUID alertId) {
    return alerts.findById(alertId).map(a -> occupiedLevels(a).pollLast()).orElse(null);
  }

  // ------------------------------------------------------------------ mon poste

  /** Alertes actives lues au plus pour « Mon poste » : la page reste rapide. */
  private static final int DESK_LIMIT = 500;

  /**
   * « Mon poste » : mes alertes en cours (prises par moi), celles qui attendent à mon niveau (sans
   * niveau pour un opérateur sans niveau), et celles qu'on m'a escaladées ou renvoyées (vers mon
   * niveau, ou en me nommant).
   */
  @Transactional(readOnly = true)
  public AlertLevelDtos.MyDesk myDesk(String username) {
    AppUser me = user(username);
    Integer level = me.getSupportLevel();
    List<AlertResponse> mine = new ArrayList<>();
    List<AlertResponse> waiting = new ArrayList<>();
    List<AlertResponse> escalated = new ArrayList<>();
    var active =
        alerts.findAll(
            (root, query, cb) ->
                root.get("state").in(List.of(AlertState.OPEN, AlertState.IN_PROGRESS)),
            org.springframework.data.domain.PageRequest.of(
                0,
                DESK_LIMIT,
                org.springframework.data.domain.Sort.by("triggeredAt").descending()));
    for (Alert alert : active) {
      if (!scope.allows(siteOf(alert)) || alert.getParentAlertId() != null) {
        continue;
      }
      List<String> assignees =
          assignments.findAllByAlertIdAndEndedAtIsNullOrderByAssignedAtAsc(alert.getId()).stream()
              .map(a -> a.getUser().getUsername())
              .toList();
      var response = AlertResponse.from(alert, assignees);
      if (alert.getState() == AlertState.IN_PROGRESS) {
        if (assignees.contains(username)) {
          mine.add(response);
        }
        continue;
      }
      if (!Objects.equals(alert.getCurrentLevel(), level)) {
        continue;
      }
      if (addressedTo(alert, me)) {
        escalated.add(response);
      } else {
        waiting.add(response);
      }
    }
    return new AlertLevelDtos.MyDesk(level, mine, waiting, escalated);
  }

  /** La dernière escalade ou le dernier renvoi de l'alerte vise mon niveau, ou me nomme. */
  private boolean addressedTo(Alert alert, AppUser me) {
    AlertEvent last = null;
    for (AlertEvent event : events.findByAlertIdOrderByOccurredAtAsc(alert.getId())) {
      if (event.getType() == AlertEventType.LEVEL_ESCALATED
          || event.getType() == AlertEventType.SENT_BACK) {
        last = event;
      }
    }
    if (last == null) {
      return false;
    }
    boolean named =
        last.getRecipients() != null
            && List.of(last.getRecipients().split(",\\s*")).contains(me.getUsername());
    return named
        || (me.getSupportLevel() != null && me.getSupportLevel().equals(last.getToLevel()));
  }

  // ------------------------------------------------------------------ journal

  /** Note courte, datée et signée, dans le journal d'intervention. */
  @Transactional
  public AlertResponse note(UUID alertId, String username, String text) {
    Alert alert = find(alertId);
    AppUser author = user(username);
    var event = new AlertEvent(alert, AlertEventType.NOTE, clock.instant());
    event.setAuthor(author);
    event.setComment(text.strip());
    events.save(event);
    audit.record("ALERT_NOTE_ADDED", "ALERT", alertId, "par=" + username);
    publisher.publishEvent(new AlertActivityEvent(alertId, AlertEventType.NOTE));
    return AlertResponse.from(alert, List.of());
  }

  /**
   * Entrée automatique du journal (diagnostic consulté, cause confirmée, action corrective). {@code
   * oncePerAuthor} : une seule ligne par personne (consulter dix fois le diagnostic n'en écrit
   * qu'une). Sans effet sur une alerte inconnue.
   */
  @Transactional
  public void logAutomatic(
      UUID alertId, AlertEventType type, String username, String text, boolean oncePerAuthor) {
    Alert alert = alerts.findById(alertId).orElse(null);
    if (alert == null) {
      return;
    }
    AppUser author = username == null ? null : users.findByUsername(username).orElse(null);
    if (oncePerAuthor
        && events.findByAlertIdOrderByOccurredAtAsc(alertId).stream()
            .anyMatch(
                e ->
                    e.getType() == type
                        && e.getAuthor() != null
                        && author != null
                        && e.getAuthor().getId().equals(author.getId()))) {
      return;
    }
    var event = new AlertEvent(alert, type, clock.instant());
    event.setAuthor(author);
    event.setComment(text == null || text.length() <= 2000 ? text : text.substring(0, 2000));
    events.save(event);
    publisher.publishEvent(new AlertActivityEvent(alertId, type));
  }

  // ------------------------------------------------------------------ escalade

  /** Où irait une escalade, et qui peut la recevoir (destinataire facultatif). */
  @Transactional(readOnly = true)
  public EscalationTarget target(UUID alertId, String username) {
    Alert alert = find(alertId);
    AppUser user = user(username);
    Integer from = alert.getCurrentLevel();
    Integer to = above(alert, base(alert, user));
    List<Candidate> candidates = new ArrayList<>();
    for (AppUser candidate : candidatesFor(alert, to)) {
      candidates.add(
          new Candidate(
              candidate.getId(),
              candidate.getUsername(),
              candidate.getRole().name(),
              candidate.getSupportLevel()));
    }
    return new EscalationTarget(from, to, candidates);
  }

  /**
   * Escalade au niveau supérieur : l'alerte redevient ouverte pour ce niveau, la ligne de la
   * chronologie porte les cases cochées, la phrase et les niveaux ; l'astreinte prévient ensuite
   * les destinataires.
   */
  @Transactional
  public AlertResponse escalate(UUID alertId, String username, EscalationRequest request) {
    Alert alert = find(alertId);
    requireActive(alert, "escalader");
    AppUser user = user(username);
    requireCanTake(user, alert);
    requireExpected(alert, request.expectedLevel());
    List<String> checks = request.checks() == null ? List.of() : request.checks();
    for (String check : checks) {
      if (!AlertLevelDtos.CHECKS.contains(check)) {
        throw bad("Case inconnue : " + check);
      }
    }
    Integer from = alert.getCurrentLevel();
    Integer to = above(alert, base(alert, user));
    if (Objects.equals(from, ADMIN_LEVEL) || (to == ADMIN_LEVEL && user.getRole() == Role.ADMIN)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Aucun niveau supérieur : l'alerte est déjà au plus haut.");
    }
    UUID recipient = request.recipientId();
    if (recipient != null
        && candidatesFor(alert, to).stream().noneMatch(c -> c.getId().equals(recipient))) {
      throw bad(
          "Le destinataire doit être un opérateur actif du niveau visé (ou un administrateur) qui"
              + " voit ce site.");
    }
    var event =
        move(alert, user, AlertEventType.LEVEL_ESCALATED, from, to, request.reason().strip());
    event.setChecklist(checks.isEmpty() ? null : String.join(",", checks));
    events.save(event);
    audit.record(
        "ALERT_LEVEL_ESCALATED",
        "ALERT",
        alertId,
        "par="
            + username
            + " de="
            + label(from)
            + " vers="
            + label(to)
            + (checks.isEmpty() ? "" : " cases=" + String.join(",", checks)));
    publisher.publishEvent(
        new AlertLevelChangedEvent(event.getId(), alertId, from, to, recipient, false));
    return AlertResponse.from(alert, List.of());
  }

  /** Renvoi au niveau inférieur occupé suivant, avec une consigne. */
  @Transactional
  public AlertResponse sendBack(UUID alertId, String username, SendBackRequest request) {
    Alert alert = find(alertId);
    requireActive(alert, "renvoyer");
    AppUser user = user(username);
    requireCanTake(user, alert);
    requireExpected(alert, request.expectedLevel());
    Integer from = alert.getCurrentLevel();
    if (from == null) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Cette alerte n'a pas de niveau : rien à renvoyer.");
    }
    Integer to = occupiedLevels(alert).lower(from);
    if (to == null) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Aucun niveau inférieur ne compte d'opérateur actif pour ce site.");
    }
    var event =
        move(alert, user, AlertEventType.SENT_BACK, from, to, request.instruction().strip());
    events.save(event);
    audit.record(
        "ALERT_SENT_BACK",
        "ALERT",
        alertId,
        "par=" + username + " de=" + label(from) + " vers=" + label(to));
    publisher.publishEvent(
        new AlertLevelChangedEvent(event.getId(), alertId, from, to, null, true));
    return AlertResponse.from(alert, List.of());
  }

  /**
   * Option de l'administrateur (désactivée par défaut) : une alerte critique monte d'office au plus
   * haut niveau occupé, sans attendre une escalade. Retourne vrai si l'alerte a changé de niveau.
   */
  @Transactional
  public boolean raiseToTop(UUID alertId) {
    Alert alert = alerts.findById(alertId).orElse(null);
    if (alert == null || alert.getState() != AlertState.OPEN) {
      return false;
    }
    Integer top = occupiedLevels(alert).pollLast();
    if (top == null || Objects.equals(top, alert.getCurrentLevel())) {
      return false;
    }
    Integer from = alert.getCurrentLevel();
    alert.setCurrentLevel(top);
    var event = new AlertEvent(alert, AlertEventType.LEVEL_ESCALATED, clock.instant());
    event.setFromLevel(from);
    event.setToLevel(top);
    event.setComment(
        "Alerte critique : montée d'office au plus haut niveau occupé (" + label(top) + ")");
    alerts.save(alert);
    events.save(event);
    publisher.publishEvent(
        new AlertLevelChangedEvent(event.getId(), alertId, from, top, null, false));
    return true;
  }

  /**
   * À l'ouverture d'une alerte critique, avec l'option de l'administrateur : montée d'office au
   * plus haut niveau occupé. Dans la transaction de l'alerte, comme les notifications.
   */
  @EventListener
  @Transactional
  public void onAlertChanged(AlertChangedEvent event) {
    if (!criticalToTop || event.type() != AlertEventType.OPENED) {
      return;
    }
    alerts
        .findById(event.alertId())
        .filter(alert -> alert.getSeverity() == Severity.CRITICAL)
        .ifPresent(alert -> raiseToTop(alert.getId()));
  }

  // ------------------------------------------------------------------ outils

  private AlertEvent move(
      Alert alert, AppUser author, AlertEventType type, Integer from, Integer to, String text) {
    Instant now = clock.instant();
    AlertState previous = alert.getState();
    // L'alerte redevient ouverte pour le niveau visé : on y relâche la prise en charge en cours.
    for (var current :
        assignments.findAllByAlertIdAndEndedAtIsNullOrderByAssignedAtAsc(alert.getId())) {
      current.setEndedAt(now);
      assignments.save(current);
    }
    alert.setState(AlertState.OPEN);
    alert.setCurrentLevel(to);
    alerts.save(alert);
    var event = new AlertEvent(alert, type, now);
    event.setAuthor(author);
    event.setPreviousState(previous);
    event.setNewState(AlertState.OPEN);
    event.setFromLevel(from);
    event.setToLevel(to);
    event.setComment(text);
    publisher.publishEvent(new AlertActivityEvent(alert.getId(), type));
    return event;
  }

  /** Niveau de départ : celui de l'alerte, sinon celui de l'auteur, sinon aucun (0). */
  private static int base(Alert alert, AppUser user) {
    if (alert.getCurrentLevel() != null) {
      return alert.getCurrentLevel();
    }
    return user.getSupportLevel() == null ? 0 : user.getSupportLevel();
  }

  /** Niveau occupé suivant au-dessus de {@code base}, sinon les administrateurs. */
  private Integer above(Alert alert, int base) {
    Integer next = base >= MAX_LEVEL ? null : occupiedLevels(alert).higher(base);
    return next == null ? ADMIN_LEVEL : next;
  }

  /** Destinataires possibles : opérateurs actifs du niveau visé, et administrateurs actifs. */
  private List<AppUser> candidatesFor(Alert alert, Integer level) {
    List<AppUser> found = new ArrayList<>();
    if (level != null && level <= MAX_LEVEL) {
      for (AppUser operator : users.findByRoleAndActiveTrue(Role.NOC_OPERATOR)) {
        if (level.equals(operator.getSupportLevel())
            && scope.allowsUser(operator.getId(), siteOf(alert))) {
          found.add(operator);
        }
      }
    }
    for (AppUser admin : users.findByRoleAndActiveTrue(Role.ADMIN)) {
      if (scope.allowsUser(admin.getId(), siteOf(alert))) {
        found.add(admin);
      }
    }
    return found;
  }

  private static void requireActive(Alert alert, String action) {
    if (alert.getState() != AlertState.OPEN && alert.getState() != AlertState.IN_PROGRESS) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Impossible de " + action + " une alerte " + alert.getState());
    }
  }

  private static void requireExpected(Alert alert, Integer expected) {
    if (expected != null && !expected.equals(alert.getCurrentLevel())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "L'alerte a changé de niveau entre-temps (" + label(alert.getCurrentLevel()) + ").");
    }
  }

  static String label(Integer level) {
    if (level == null) {
      return "sans niveau";
    }
    return level == ADMIN_LEVEL ? "administrateurs" : "N" + level;
  }

  private Alert find(UUID id) {
    return alerts
        .findById(id)
        .filter(alert -> alert.getState() != AlertState.DELETED)
        .filter(alert -> scope.allows(siteOf(alert)))
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Alerte introuvable"));
  }

  private AppUser user(String username) {
    return users
        .findByUsername(username)
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Utilisateur inconnu"));
  }

  private static UUID siteOf(Alert alert) {
    var site = alert.getEquipment().getSite();
    return site == null ? null : site.getId();
  }

  private static ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }
}

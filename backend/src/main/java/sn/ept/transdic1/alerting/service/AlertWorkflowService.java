package sn.ept.transdic1.alerting.service;

import jakarta.persistence.criteria.Predicate;
import java.time.Clock;
import java.util.ArrayList;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import sn.ept.transdic1.alerting.domain.Alert;
import sn.ept.transdic1.alerting.domain.AlertActivityEvent;
import sn.ept.transdic1.alerting.domain.AlertAssignment;
import sn.ept.transdic1.alerting.domain.AlertEvent;
import sn.ept.transdic1.alerting.domain.AlertEventType;
import sn.ept.transdic1.alerting.domain.AlertState;
import sn.ept.transdic1.alerting.domain.ResolutionType;
import sn.ept.transdic1.alerting.dto.AlertDetailResponse;
import sn.ept.transdic1.alerting.dto.AlertEventResponse;
import sn.ept.transdic1.alerting.dto.AlertResponse;
import sn.ept.transdic1.alerting.repository.AlertAssignmentRepository;
import sn.ept.transdic1.alerting.repository.AlertEventRepository;
import sn.ept.transdic1.alerting.repository.AlertRepository;
import sn.ept.transdic1.audit.service.AuditService;
import sn.ept.transdic1.security.domain.AppUser;
import sn.ept.transdic1.security.domain.Role;
import sn.ept.transdic1.security.repository.UserRepository;

/**
 * Cycle de vie manuel d'une alerte, selon le diagramme d'états validé : prise en charge,
 * assignation, résolution, archivage, suppression. Chaque action vérifie la transition, ajoute un
 * événement à l'historique et laisse une entrée d'audit.
 */
@Service
public class AlertWorkflowService {
  private final AlertRepository alerts;
  private final AlertEventRepository events;
  private final AlertAssignmentRepository assignments;
  private final UserRepository users;
  private final AuditService audit;
  private final Clock clock;
  private final ApplicationEventPublisher publisher;

  public AlertWorkflowService(
      AlertRepository alerts,
      AlertEventRepository events,
      AlertAssignmentRepository assignments,
      UserRepository users,
      AuditService audit,
      Clock clock,
      ApplicationEventPublisher publisher) {
    this.alerts = alerts;
    this.events = events;
    this.assignments = assignments;
    this.users = users;
    this.audit = audit;
    this.clock = clock;
    this.publisher = publisher;
  }

  // ------------------------------------------------------------------ lecture

  @Transactional(readOnly = true)
  public Page<AlertResponse> list(AlertState state, UUID equipmentId, Pageable pageable) {
    Specification<Alert> spec =
        (root, query, cb) -> {
          var predicates = new ArrayList<Predicate>();
          // Une alerte supprimée n'apparaît plus, sauf si on la demande explicitement.
          if (state == null) {
            predicates.add(cb.notEqual(root.get("state"), AlertState.DELETED));
          } else {
            predicates.add(cb.equal(root.get("state"), state));
          }
          if (equipmentId != null) {
            predicates.add(cb.equal(root.get("equipment").get("id"), equipmentId));
          }
          return cb.and(predicates.toArray(Predicate[]::new));
        };
    return alerts.findAll(spec, pageable).map(alert -> AlertResponse.from(alert, assignees(alert)));
  }

  @Transactional(readOnly = true)
  public AlertDetailResponse get(UUID id) {
    Alert alert = find(id);
    return new AlertDetailResponse(
        AlertResponse.from(alert, assignees(alert)),
        events.findByAlertIdOrderByOccurredAtAsc(id).stream()
            .map(AlertEventResponse::from)
            .toList());
  }

  // ------------------------------------------------------------------ actions

  /** Un opérateur prend l'alerte ouverte en charge. */
  @Transactional
  public AlertResponse take(UUID id, String username, String comment) {
    Alert alert = find(id);
    requireState(alert, "prendre en charge", AlertState.OPEN);
    AppUser user = user(username);
    startAssignment(alert, user);
    transition(alert, AlertState.IN_PROGRESS, AlertEventType.TAKEN, user, comment);
    audit.record("ALERT_TAKEN", "ALERT", id, "par=" + username);
    return AlertResponse.from(alert, assignees(alert));
  }

  /** Un administrateur assigne (ou réassigne) l'alerte à un opérateur. */
  @Transactional
  public AlertResponse assign(UUID id, UUID assigneeId, String byUsername, String comment) {
    Alert alert = find(id);
    requireState(alert, "assigner", AlertState.OPEN, AlertState.IN_PROGRESS);
    AppUser assignee = eligible(assigneeId);
    AppUser by = user(byUsername);
    var now = clock.instant();
    for (var current : assignments.findAllByAlertIdAndEndedAtIsNullOrderByAssignedAtAsc(id)) {
      current.setEndedAt(now);
      assignments.saveAndFlush(current);
      var released = new AlertEvent(alert, AlertEventType.UNASSIGNED, now);
      released.setAuthor(by);
      released.setComment("Retiré à " + current.getUser().getUsername());
      events.save(released);
    }
    startAssignment(alert, assignee);
    AlertState previous = alert.getState();
    var event = new AlertEvent(alert, AlertEventType.ASSIGNED, now);
    event.setAuthor(by);
    event.setPreviousState(previous);
    event.setNewState(AlertState.IN_PROGRESS);
    event.setComment(comment == null ? "Assignée à " + assignee.getUsername() : comment);
    alert.setState(AlertState.IN_PROGRESS);
    if (alert.getTakenAt() == null) {
      alert.setTakenAt(now);
    }
    alerts.save(alert);
    events.save(event);
    publisher.publishEvent(new AlertActivityEvent(alert.getId(), AlertEventType.ASSIGNED));
    audit.record(
        "ALERT_ASSIGNED", "ALERT", id, "a=" + assignee.getUsername() + " par=" + byUsername);
    return AlertResponse.from(alert, assignees(alert));
  }

  /**
   * Ajoute un opérateur au suivi de l'alerte sans retirer ceux qui y sont déjà (assignation
   * multiple). Sans effet si l'opérateur suit déjà l'alerte.
   */
  @Transactional
  public AlertResponse addAssignee(UUID id, UUID assigneeId, String byUsername, String comment) {
    Alert alert = find(id);
    requireState(alert, "assigner", AlertState.OPEN, AlertState.IN_PROGRESS);
    AppUser assignee = eligible(assigneeId);
    boolean already =
        assignments.findAllByAlertIdAndEndedAtIsNullOrderByAssignedAtAsc(id).stream()
            .anyMatch(a -> a.getUser().getId().equals(assignee.getId()));
    if (already) {
      return AlertResponse.from(alert, assignees(alert));
    }
    AppUser by = user(byUsername);
    var now = clock.instant();
    startAssignment(alert, assignee);
    AlertState previous = alert.getState();
    alert.setState(AlertState.IN_PROGRESS);
    if (alert.getTakenAt() == null) {
      alert.setTakenAt(now);
    }
    var event = new AlertEvent(alert, AlertEventType.ASSIGNED, now);
    event.setAuthor(by);
    event.setPreviousState(previous);
    event.setNewState(AlertState.IN_PROGRESS);
    event.setComment(
        comment == null ? "Ajout de " + assignee.getUsername() + " au suivi" : comment);
    alerts.save(alert);
    events.save(event);
    publisher.publishEvent(new AlertActivityEvent(alert.getId(), AlertEventType.ASSIGNED));
    audit.record(
        "ALERT_ASSIGNEE_ADDED", "ALERT", id, "a=" + assignee.getUsername() + " par=" + byUsername);
    return AlertResponse.from(alert, assignees(alert));
  }

  /** Retire un opérateur du suivi ; l'alerte reste prise en charge par les autres. */
  @Transactional
  public AlertResponse removeAssignee(UUID id, UUID assigneeId, String byUsername) {
    Alert alert = find(id);
    requireState(alert, "retirer un assigné", AlertState.OPEN, AlertState.IN_PROGRESS);
    var current =
        assignments.findAllByAlertIdAndEndedAtIsNullOrderByAssignedAtAsc(id).stream()
            .filter(a -> a.getUser().getId().equals(assigneeId))
            .findFirst()
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Cet opérateur ne suit pas l'alerte"));
    var now = clock.instant();
    current.setEndedAt(now);
    assignments.save(current);
    var event = new AlertEvent(alert, AlertEventType.UNASSIGNED, now);
    event.setAuthor(user(byUsername));
    event.setComment("Retiré à " + current.getUser().getUsername());
    events.save(event);
    publisher.publishEvent(new AlertActivityEvent(alert.getId(), AlertEventType.UNASSIGNED));
    audit.record(
        "ALERT_ASSIGNEE_REMOVED",
        "ALERT",
        id,
        "a=" + current.getUser().getUsername() + " par=" + byUsername);
    return AlertResponse.from(alert, assignees(alert));
  }

  /** Résolution manuelle, par un opérateur ou un administrateur. */
  @Transactional
  public AlertResponse resolve(UUID id, String username, String comment) {
    Alert alert = find(id);
    requireState(alert, "résoudre", AlertState.OPEN, AlertState.IN_PROGRESS);
    AppUser user = user(username);
    var now = clock.instant();
    endAssignments(id, now);
    alert.setResolvedAt(now);
    alert.setResolutionType(ResolutionType.MANUAL);
    transition(alert, AlertState.RESOLVED, AlertEventType.RESOLVED, user, comment);
    audit.record("ALERT_RESOLVED", "ALERT", id, "par=" + username);
    return AlertResponse.from(alert, java.util.List.of());
  }

  @Transactional
  public AlertResponse archive(UUID id, String username) {
    Alert alert = find(id);
    requireState(alert, "archiver", AlertState.RESOLVED);
    AppUser user = user(username);
    alert.setArchivedAt(clock.instant());
    transition(alert, AlertState.ARCHIVED, AlertEventType.ARCHIVED, user, null);
    audit.record("ALERT_ARCHIVED", "ALERT", id, "par=" + username);
    return AlertResponse.from(alert, java.util.List.of());
  }

  /** Suppression logique : l'alerte et son historique restent en base. */
  @Transactional
  public void delete(UUID id, String username) {
    Alert alert = find(id);
    requireState(
        alert,
        "supprimer",
        AlertState.OPEN,
        AlertState.IN_PROGRESS,
        AlertState.RESOLVED,
        AlertState.ARCHIVED);
    AppUser user = user(username);
    var now = clock.instant();
    endAssignments(id, now);
    alert.setDeletedAt(now);
    transition(alert, AlertState.DELETED, AlertEventType.DELETED, user, null);
    audit.record("ALERT_DELETED", "ALERT", id, "par=" + username);
  }

  // ------------------------------------------------------------------ outils

  private void startAssignment(Alert alert, AppUser user) {
    assignments.save(new AlertAssignment(alert, user, clock.instant()));
  }

  private void transition(
      Alert alert, AlertState next, AlertEventType type, AppUser author, String comment) {
    var now = clock.instant();
    AlertState previous = alert.getState();
    alert.setState(next);
    if (next == AlertState.IN_PROGRESS && alert.getTakenAt() == null) {
      alert.setTakenAt(now);
    }
    var event = new AlertEvent(alert, type, now);
    event.setAuthor(author);
    event.setPreviousState(previous);
    event.setNewState(next);
    event.setComment(comment);
    alerts.save(alert);
    events.save(event);
    publisher.publishEvent(new AlertActivityEvent(alert.getId(), type));
  }

  private Alert find(UUID id) {
    return alerts
        .findById(id)
        .filter(alert -> alert.getState() != AlertState.DELETED)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Alerte introuvable"));
  }

  private AppUser eligible(UUID assigneeId) {
    return users
        .findById(assigneeId)
        .filter(AppUser::isActive)
        .filter(u -> u.getRole() == Role.NOC_OPERATOR || u.getRole() == Role.ADMIN)
        .orElseThrow(
            () ->
                new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "L'assigné doit être un opérateur ou un administrateur actif"));
  }

  private AppUser user(String username) {
    return users
        .findByUsername(username)
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Utilisateur inconnu"));
  }

  private java.util.List<String> assignees(Alert alert) {
    return assignments.findAllByAlertIdAndEndedAtIsNullOrderByAssignedAtAsc(alert.getId()).stream()
        .map(assignment -> assignment.getUser().getUsername())
        .toList();
  }

  private void endAssignments(UUID alertId, java.time.Instant now) {
    for (var current : assignments.findAllByAlertIdAndEndedAtIsNullOrderByAssignedAtAsc(alertId)) {
      current.setEndedAt(now);
      assignments.save(current);
    }
  }

  private static void requireState(Alert alert, String action, AlertState... allowed) {
    for (AlertState state : allowed) {
      if (alert.getState() == state) {
        return;
      }
    }
    throw new ResponseStatusException(
        HttpStatus.CONFLICT,
        "Impossible de " + action + " une alerte à l'état " + alert.getState());
  }
}

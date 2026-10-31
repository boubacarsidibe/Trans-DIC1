package sn.ept.transdic1.security.service;

import java.time.Clock;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import sn.ept.transdic1.security.domain.AppUser;
import sn.ept.transdic1.security.domain.Role;
import sn.ept.transdic1.security.dto.UserCreateRequest;
import sn.ept.transdic1.security.dto.UserResponse;
import sn.ept.transdic1.security.repository.UserRepository;
import sn.ept.transdic1.security.repository.UserSessionRepository;

/**
 * Gestion des comptes par les administrateurs. Garde-fous : on ne se retire pas ses propres droits,
 * le dernier administrateur actif ne peut ni être rétrogradé ni désactivé, et tout changement de
 * droits ou de mot de passe révoque les connexions de l'utilisateur concerné.
 */
@Service
public class UserAdminService {
  private final UserRepository users;
  private final UserSessionRepository sessions;
  private final PasswordEncoder encoder;
  private final SecurityAuditLog audit;
  private final Clock clock;

  public UserAdminService(
      UserRepository users,
      UserSessionRepository sessions,
      PasswordEncoder encoder,
      SecurityAuditLog audit,
      Clock clock) {
    this.users = users;
    this.sessions = sessions;
    this.encoder = encoder;
    this.audit = audit;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public Page<UserResponse> list(Pageable pageable) {
    return users.findAll(pageable).map(UserResponse::from);
  }

  @Transactional
  public UserResponse create(UserCreateRequest request, String actor) {
    if (users.existsByUsername(request.username())) {
      throw conflict("Ce nom d'utilisateur est déjà pris");
    }
    if (users.existsByEmail(request.email())) {
      throw conflict("Cette adresse e-mail est déjà utilisée");
    }
    if (request.password().toLowerCase().contains(request.username().toLowerCase())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Le mot de passe ne doit pas contenir le nom d'utilisateur");
    }
    var user =
        users.save(
            new AppUser(
                request.username(),
                request.email(),
                encoder.encode(request.password()),
                request.role()));
    // Mot de passe choisi par l'administrateur : temporaire, à changer à la première connexion.
    user.setPasswordChangeRequired(true);
    users.save(user);
    audit.recordAdminAction(
        "USER_CREATED",
        actor,
        user.getId(),
        "compte=" + user.getUsername() + " role=" + user.getRole() + " motDePasse=temporaire");
    return UserResponse.from(user);
  }

  @Transactional
  public UserResponse changeRole(UUID id, Role role, String actor) {
    var user = find(id);
    requireNotSelf(user, actor);
    if (user.getRole() == Role.ADMIN && role != Role.ADMIN) {
      requireAnotherActiveAdmin(user);
    }
    Role previous = user.getRole();
    user.changeRole(role);
    users.save(user);
    revokeSessions(user);
    audit.recordAdminAction(
        "USER_ROLE_CHANGED",
        actor,
        id,
        "compte=" + user.getUsername() + " " + previous + " -> " + role);
    return UserResponse.from(user);
  }

  @Transactional
  public UserResponse setActive(UUID id, boolean active, String actor) {
    var user = find(id);
    requireNotSelf(user, actor);
    if (!active && user.getRole() == Role.ADMIN && user.isActive()) {
      requireAnotherActiveAdmin(user);
    }
    user.setActive(active);
    users.save(user);
    if (!active) {
      revokeSessions(user);
    }
    audit.recordAdminAction(
        active ? "USER_ACTIVATED" : "USER_DEACTIVATED", actor, id, "compte=" + user.getUsername());
    return UserResponse.from(user);
  }

  @Transactional
  public void resetPassword(UUID id, String newPassword, String actor) {
    var user = find(id);
    if (newPassword.toLowerCase().contains(user.getUsername().toLowerCase())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Le mot de passe ne doit pas contenir le nom d'utilisateur");
    }
    user.resetPassword(encoder.encode(newPassword));
    user.setPasswordChangeRequired(true);
    users.save(user);
    revokeSessions(user);
    audit.recordAdminAction("USER_PASSWORD_RESET", actor, id, "compte=" + user.getUsername());
  }

  // ------------------------------------------------------------------ outils

  private void revokeSessions(AppUser user) {
    var now = clock.instant();
    for (var session : sessions.findByUserIdAndRevokedAtIsNull(user.getId())) {
      session.setRevokedAt(now);
      sessions.save(session);
    }
  }

  private void requireNotSelf(AppUser user, String actor) {
    if (user.getUsername().equals(actor)) {
      throw conflict("Vous ne pouvez pas modifier votre propre rôle ou statut");
    }
  }

  private void requireAnotherActiveAdmin(AppUser user) {
    if (user.isActive() && users.countByRoleAndActiveTrue(Role.ADMIN) <= 1) {
      throw conflict("Il doit rester au moins un administrateur actif");
    }
  }

  private AppUser find(UUID id) {
    return users
        .findById(id)
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Utilisateur introuvable"));
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }
}

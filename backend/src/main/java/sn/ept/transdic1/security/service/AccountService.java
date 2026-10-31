package sn.ept.transdic1.security.service;

import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import sn.ept.transdic1.security.domain.AppUser;
import sn.ept.transdic1.security.domain.Role;
import sn.ept.transdic1.security.repository.UserRepository;

/**
 * État courant d'un compte, lu à chaque requête authentifiée (FEAT-01) : un compte désactivé, ou
 * dont le rôle a changé, perd l'accès tout de suite au lieu d'attendre l'expiration de son jeton.
 * Changement du mot de passe temporaire par l'utilisateur lui-même.
 */
@Service
public class AccountService {
  /** Ce que le filtre JWT doit savoir du compte : une requête indexée sur le nom d'utilisateur. */
  public record AccountStatus(boolean active, Role role, boolean passwordChangeRequired) {}

  private final UserRepository users;
  private final PasswordEncoder encoder;
  private final SecurityAuditLog audit;

  public AccountService(UserRepository users, PasswordEncoder encoder, SecurityAuditLog audit) {
    this.users = users;
    this.encoder = encoder;
    this.audit = audit;
  }

  @Transactional(readOnly = true)
  public Optional<AccountStatus> status(String username) {
    return users
        .findByUsername(username)
        .map(u -> new AccountStatus(u.isActive(), u.getRole(), u.isPasswordChangeRequired()));
  }

  /**
   * Remplace le mot de passe après vérification de l'actuel ; lève l'obligation de changement. Le
   * nouveau mot de passe doit différer de l'actuel et ne pas contenir le nom d'utilisateur. Un mot
   * de passe actuel erroné reste tracé malgré l'exception, d'où noRollbackFor.
   */
  @Transactional(noRollbackFor = ResponseStatusException.class)
  public void changePassword(String username, String currentPassword, String newPassword) {
    AppUser user =
        users
            .findByUsername(username)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    if (!encoder.matches(currentPassword, user.getPasswordHash())) {
      audit.record("PASSWORD_CHANGE_FAILED", username, username);
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mot de passe actuel incorrect");
    }
    if (encoder.matches(newPassword, user.getPasswordHash())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Le nouveau mot de passe doit être différent de l'actuel");
    }
    if (newPassword.toLowerCase().contains(username.toLowerCase())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Le mot de passe ne doit pas contenir le nom d'utilisateur");
    }
    user.resetPassword(encoder.encode(newPassword));
    user.setPasswordChangeRequired(false);
    users.save(user);
    audit.record("PASSWORD_CHANGED", username, username);
  }
}

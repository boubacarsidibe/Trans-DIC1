package sn.ept.transdic1.security.service;

import java.time.Clock;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import sn.ept.transdic1.security.domain.AppUser;
import sn.ept.transdic1.security.repository.UserRepository;

/**
 * Double authentification par code à usage unique (TOTP). Trois temps, pour qu'on ne puisse pas se
 * verrouiller dehors : on <b>prépare</b> (un secret est créé et montré une fois), on <b>active</b>
 * en saisissant un premier code — ce qui prouve que l'application est bien configurée —, et on peut
 * <b>couper</b> en redonnant son mot de passe.
 *
 * <p>Un secret préparé mais jamais confirmé n'exige rien à la connexion. Un administrateur peut
 * couper la double authentification d'un compte dont le téléphone est perdu : sans cela, la
 * personne serait bloquée.
 */
@Service
public class TwoFactorService {
  /** Nom affiché dans l'application d'authentification. */
  static final String ISSUER = "Trans-DIC1";

  private final UserRepository users;
  private final PasswordEncoder passwords;
  private final SecurityAuditLog audit;
  private final Clock clock;

  public TwoFactorService(
      UserRepository users, PasswordEncoder passwords, SecurityAuditLog audit, Clock clock) {
    this.users = users;
    this.passwords = passwords;
    this.audit = audit;
    this.clock = clock;
  }

  /** Ce que l'écran montre après la préparation : à scanner ou à saisir, une seule fois. */
  public record Preparation(String secret, String uri) {}

  /** État de la double authentification d'un compte. */
  public record Status(boolean enabled, boolean prepared) {}

  @Transactional(readOnly = true)
  public Status status(String username) {
    AppUser user = find(username);
    return new Status(user.isTotpEnabled(), user.getTotpSecret() != null);
  }

  /**
   * Crée un secret et le rend une seule fois. Tant qu'aucun code n'a confirmé, la connexion reste
   * inchangée ; préparer deux fois remplace simplement le secret précédent.
   */
  @Transactional
  public Preparation prepare(String username) {
    AppUser user = find(username);
    if (user.isTotpEnabled()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "La double authentification est déjà active : coupez-la avant d'en configurer une autre");
    }
    String secret = Totp.newSecret();
    user.prepareTotp(secret);
    users.save(user);
    return new Preparation(secret, Totp.uri(ISSUER, user.getUsername(), secret));
  }

  /** Active la double authentification après vérification d'un premier code. */
  @Transactional(noRollbackFor = ResponseStatusException.class)
  public void activate(String username, String code) {
    AppUser user = find(username);
    if (user.getTotpSecret() == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Commencez par préparer la double authentification");
    }
    if (!Totp.verify(user.getTotpSecret(), code, clock.instant())) {
      audit.record("TOTP_ACTIVATION_FAILED", user.getUsername(), user.getUsername());
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Code incorrect");
    }
    user.enableTotp();
    users.save(user);
    audit.record("TOTP_ENABLED", user.getUsername(), user.getUsername());
  }

  /**
   * Coupe la double authentification ; le mot de passe est redemandé pour éviter un détournement.
   */
  @Transactional(noRollbackFor = ResponseStatusException.class)
  public void disable(String username, String password) {
    AppUser user = find(username);
    if (!passwords.matches(password == null ? "" : password, user.getPasswordHash())) {
      audit.record("TOTP_DISABLE_FAILED", user.getUsername(), user.getUsername());
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mot de passe incorrect");
    }
    user.disableTotp();
    users.save(user);
    audit.record("TOTP_DISABLED", user.getUsername(), user.getUsername());
  }

  /**
   * Coupe la double authentification d'un autre compte (téléphone perdu). Réservé aux
   * administrateurs par le contrôleur ; tracé au nom de celui qui agit.
   */
  @Transactional
  public void disableFor(java.util.UUID userId, String actor) {
    AppUser user =
        users
            .findById(userId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Compte introuvable"));
    user.disableTotp();
    users.save(user);
    audit.record("TOTP_RESET_BY_ADMIN", actor, user.getUsername());
  }

  private AppUser find(String username) {
    return users
        .findByUsername(username)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Compte introuvable"));
  }
}

package sn.ept.transdic1.security.service;

import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import sn.ept.transdic1.security.domain.AppUser;
import sn.ept.transdic1.security.dto.*;
import sn.ept.transdic1.security.repository.UserRepository;

@Service
public class AuthService {
  private static final String INVALID = "Identifiants invalides";
  /** Hash factice : le cout est identique que le compte existe ou non. */
  private final String dummyHash;

  private final UserRepository users;
  private final PasswordEncoder passwords;
  private final JwtService jwt;
  private final Clock clock;
  private final SecurityAuditLog audit;
  private final int lockThreshold;
  private final Duration baseLock;
  private final Duration maxLock;

  public AuthService(
      UserRepository u,
      PasswordEncoder p,
      JwtService j,
      Clock clock,
      SecurityAuditLog audit,
      @Value("${security.login.lock-threshold:5}") int lockThreshold,
      @Value("${security.login.lock-base-seconds:60}") long baseSeconds,
      @Value("${security.login.lock-max-seconds:1800}") long maxSeconds) {
    users = u;
    passwords = p;
    dummyHash = p.encode(java.util.UUID.randomUUID().toString());
    jwt = j;
    this.clock = clock;
    this.audit = audit;
    this.lockThreshold = lockThreshold;
    this.baseLock = Duration.ofSeconds(baseSeconds);
    this.maxLock = Duration.ofSeconds(maxSeconds);
  }

  /**
   * Toutes les causes d'échec (inconnu, désactivé, verrouillé, mauvais mot de passe) renvoient la
   * même réponse 401. L'échec doit être enregistré malgré l'exception, d'où noRollbackFor.
   */
  @Transactional(noRollbackFor = ResponseStatusException.class)
  public TokenResponse login(LoginRequest r) {
    var now = clock.instant();
    var found = users.findByUsername(r.username());

    if (found.isEmpty()) {
      passwords.matches(r.password(), dummyHash);
      audit.record("LOGIN_FAILED_UNKNOWN", null, r.username());
      throw unauthorized();
    }

    AppUser u = found.get();
    if (!u.isActive() || u.isLocked(now)) {
      passwords.matches(r.password(), dummyHash);
      audit.record(u.isActive() ? "LOGIN_BLOCKED_LOCKED" : "LOGIN_BLOCKED_INACTIVE", u.getUsername(), r.username());
      throw unauthorized();
    }

    if (!passwords.matches(r.password(), u.getPasswordHash())) {
      u.registerFailedLogin(now, lockThreshold, baseLock, maxLock);
      users.save(u);
      audit.record(u.isLocked(now) ? "ACCOUNT_LOCKED" : "LOGIN_FAILED", u.getUsername(), r.username());
      throw unauthorized();
    }

    u.registerSuccessfulLogin();
    users.save(u);
    return new TokenResponse(jwt.issue(u), "Bearer", jwt.expirationSeconds(), u.getRole().name());
  }

  private static ResponseStatusException unauthorized() {
    return new ResponseStatusException(HttpStatus.UNAUTHORIZED, INVALID);
  }
}

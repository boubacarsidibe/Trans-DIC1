package sn.ept.transdic1.security.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import sn.ept.transdic1.security.domain.AppUser;
import sn.ept.transdic1.security.domain.UserSession;
import sn.ept.transdic1.security.dto.*;
import sn.ept.transdic1.security.repository.UserRepository;
import sn.ept.transdic1.security.repository.UserSessionRepository;

@Service
public class AuthService {
  private static final String INVALID = "Identifiants invalides";

  /** Hash factice : le cout est identique que le compte existe ou non. */
  private final String dummyHash;

  private static final SecureRandom RANDOM = new SecureRandom();

  private final UserRepository users;
  private final UserSessionRepository sessions;
  private final Duration refreshLifetime;
  private final PasswordEncoder passwords;
  private final JwtService jwt;
  private final Clock clock;
  private final SecurityAuditLog audit;
  private final int lockThreshold;
  private final Duration baseLock;
  private final Duration maxLock;

  public AuthService(
      UserRepository u,
      UserSessionRepository sessions,
      PasswordEncoder p,
      JwtService j,
      Clock clock,
      SecurityAuditLog audit,
      @Value("${security.login.lock-threshold:5}") int lockThreshold,
      @Value("${security.login.lock-base-seconds:60}") long baseSeconds,
      @Value("${security.login.lock-max-seconds:1800}") long maxSeconds,
      @Value("${security.refresh.max-age-seconds:28800}") long refreshSeconds) {
    users = u;
    this.sessions = sessions;
    this.refreshLifetime = Duration.ofSeconds(refreshSeconds);
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
  public AuthSession login(LoginRequest r, String ip) {
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
      audit.record(
          u.isActive() ? "LOGIN_BLOCKED_LOCKED" : "LOGIN_BLOCKED_INACTIVE",
          u.getUsername(),
          r.username());
      throw unauthorized();
    }

    if (!passwords.matches(r.password(), u.getPasswordHash())) {
      u.registerFailedLogin(now, lockThreshold, baseLock, maxLock);
      users.save(u);
      audit.record(
          u.isLocked(now) ? "ACCOUNT_LOCKED" : "LOGIN_FAILED", u.getUsername(), r.username());
      throw unauthorized();
    }

    u.registerSuccessfulLogin();
    users.save(u);
    String refreshToken = newRefreshToken();
    var session = new UserSession(u, hash(refreshToken), now, now.plus(refreshLifetime), null);
    session.setIpAddress(ip);
    sessions.save(session);
    return authSession(u, refreshToken, now.plus(refreshLifetime), now);
  }

  /**
   * Remplace le jeton de renouvellement par le suivant (rotation). Présenter un jeton déjà remplacé
   * ou révoqué révoque toute la famille : quelqu'un d'autre l'a peut-être copié (ADR-0005).
   */
  @Transactional(noRollbackFor = ResponseStatusException.class)
  public AuthSession refresh(String refreshToken, String ip) {
    var now = clock.instant();
    if (refreshToken == null || refreshToken.isBlank()) {
      throw unauthorized();
    }
    var found = sessions.findByTokenHash(hash(refreshToken));
    if (found.isEmpty()) {
      audit.record("REFRESH_UNKNOWN", null, "refresh");
      throw unauthorized();
    }
    UserSession current = found.get();
    AppUser u = current.getUser();

    if (current.getRevokedAt() != null) {
      revokeFamily(current, now);
      audit.record("REFRESH_REUSE_DETECTED", u.getUsername(), "refresh");
      throw unauthorized();
    }
    if (current.isExpired(now)) {
      throw unauthorized();
    }
    if (!u.isActive() || u.isLocked(now)) {
      revokeFamily(current, now);
      audit.record("REFRESH_BLOCKED", u.getUsername(), "refresh");
      throw unauthorized();
    }

    String nextToken = newRefreshToken();
    // La durée maximale de la connexion ne se prolonge pas à chaque renouvellement.
    var next =
        new UserSession(u, hash(nextToken), now, current.getExpiresAt(), current.getFamilyId());
    next.setIpAddress(ip);
    current.rotate(now, next);
    sessions.save(next);
    sessions.save(current);
    return authSession(u, nextToken, current.getExpiresAt(), now);
  }

  /** Révoque la connexion dont le jeton est présenté. Silencieux si le jeton est inconnu. */
  @Transactional
  public void logout(String refreshToken) {
    if (refreshToken == null || refreshToken.isBlank()) {
      return;
    }
    sessions
        .findByTokenHash(hash(refreshToken))
        .ifPresent(session -> revokeFamily(session, clock.instant()));
  }

  private void revokeFamily(UserSession session, Instant now) {
    for (UserSession member : sessions.findByFamilyIdAndRevokedAtIsNull(session.getFamilyId())) {
      member.setRevokedAt(now);
      sessions.save(member);
    }
  }

  private AuthSession authSession(AppUser u, String refreshToken, Instant expiresAt, Instant now) {
    long remaining = Math.max(Duration.between(now, expiresAt).getSeconds(), 0);
    var token =
        new TokenResponse(jwt.issue(u), "Bearer", jwt.expirationSeconds(), u.getRole().name());
    return new AuthSession(token, refreshToken, remaining);
  }

  private static String newRefreshToken() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /** Seule l'empreinte du jeton est stockée : une fuite de la base ne donne pas de session. */
  static String hash(String token) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static ResponseStatusException unauthorized() {
    return new ResponseStatusException(HttpStatus.UNAUTHORIZED, INVALID);
  }
}

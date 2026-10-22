package sn.ept.transdic1.security.domain;

import jakarta.persistence.*;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "app_user")
public class AppUser {
  @Id private UUID id;

  @Column(nullable = false, unique = true)
  private String username;

  @Column(nullable = false, unique = true)
  private String email;

  @Column(name = "password_hash", nullable = false)
  private String passwordHash;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Role role;

  @Column(nullable = false)
  private boolean active;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "failed_login_attempts", nullable = false)
  private int failedLoginAttempts;

  @Column(name = "locked_until")
  private Instant lockedUntil;

  protected AppUser() {}

  public AppUser(String u, String e, String p, Role r) {
    id = UUID.randomUUID();
    username = u;
    email = e;
    passwordHash = p;
    role = r;
    active = true;
    createdAt = Instant.now();
  }

  public String getUsername() {
    return username;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public Role getRole() {
    return role;
  }

  public boolean isActive() {
    return active;
  }

  public int getFailedLoginAttempts() {
    return failedLoginAttempts;
  }

  public boolean isLocked(Instant now) {
    return lockedUntil != null && now.isBefore(lockedUntil);
  }

  /** Enregistre un échec ; verrouille le compte dès le seuil atteint, de plus en plus longtemps. */
  public void registerFailedLogin(Instant now, int threshold, Duration baseLock, Duration maxLock) {
    failedLoginAttempts++;
    if (failedLoginAttempts >= threshold) {
      int doublings = Math.min(failedLoginAttempts - threshold, 20);
      Duration lock = baseLock.multipliedBy(1L << doublings);
      lockedUntil = now.plus(lock.compareTo(maxLock) > 0 ? maxLock : lock);
    }
  }

  public void registerSuccessfulLogin() {
    failedLoginAttempts = 0;
    lockedUntil = null;
  }
}

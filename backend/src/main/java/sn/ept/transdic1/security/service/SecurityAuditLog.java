package sn.ept.transdic1.security.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Journalise les événements de sécurité, sans jamais écrire de mot de passe ni de jeton. */
@Component
public class SecurityAuditLog {
  private static final Logger log = LoggerFactory.getLogger(SecurityAuditLog.class);
  private final JdbcTemplate jdbc;

  public SecurityAuditLog(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * @param knownUsername nom du compte existant, ou null si l'identifiant est inconnu : un
   *     identifiant saisi peut être un mot de passe tapé au mauvais endroit, il n'est jamais stocké.
   */
  public void record(String action, String knownUsername, String attemptedIdentifier) {
    String fingerprint = fingerprint(attemptedIdentifier);
    log.warn("security_event action={} identifier={}", action, fingerprint);
    jdbc.update(
        "INSERT INTO audit_entry (id, username, action, target_type, details_sanitized) "
            + "VALUES (?, ?, ?, 'AUTH', ?)",
        UUID.randomUUID(),
        knownUsername,
        action,
        "empreinte=" + fingerprint);
  }

  static String fingerprint(String value) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash, 0, 4);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}

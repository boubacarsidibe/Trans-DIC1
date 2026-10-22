package sn.ept.transdic1.audit.service;

import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.audit.domain.AuditEntry;
import sn.ept.transdic1.audit.repository.AuditEntryRepository;
import sn.ept.transdic1.security.domain.AppUser;
import sn.ept.transdic1.security.repository.UserRepository;

/** Journalise les actions sensibles dans la transaction de l'action, avec l'auteur courant. */
@Service
public class AuditService {
  private static final int MAX_DETAILS = 1000;
  private final AuditEntryRepository entries;
  private final UserRepository users;

  public AuditService(AuditEntryRepository entries, UserRepository users) {
    this.entries = entries;
    this.users = users;
  }

  @Transactional
  public void record(String action, String targetType, UUID targetId, String details) {
    String username = currentUsername();
    AppUser author = username == null ? null : users.findByUsername(username).orElse(null);
    entries.save(
        new AuditEntry(
            author,
            username,
            action,
            targetType,
            targetId,
            MDC.get("correlationId"),
            sanitize(details)));
  }

  private static String currentUsername() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getName())) {
      return null;
    }
    return auth.getName();
  }

  /** Retire les caractères de contrôle (injection dans les journaux) et borne la taille. */
  static String sanitize(String details) {
    if (details == null) return null;
    String clean = details.replaceAll("\\p{Cntrl}", " ").trim();
    return clean.length() > MAX_DETAILS ? clean.substring(0, MAX_DETAILS) : clean;
  }
}

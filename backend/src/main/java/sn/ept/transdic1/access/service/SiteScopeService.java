package sn.ept.transdic1.access.service;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import sn.ept.transdic1.access.repository.SiteAccessRepository;
import sn.ept.transdic1.common.scope.SiteScope;
import sn.ept.transdic1.security.domain.Role;
import sn.ept.transdic1.security.repository.UserRepository;

/**
 * Calcule le périmètre d'accès par site (ADR 0018). Lu à chaque requête : un changement de
 * périmètre s'applique tout de suite, comme un changement de rôle. Le résultat est gardé le temps
 * d'une requête HTTP pour ne pas relire la base à chaque filtre.
 */
@Service
@Transactional(readOnly = true)
public class SiteScopeService implements SiteScope {
  private static final String CACHE = SiteScopeService.class.getName() + ".restriction";
  private static final String ADMIN = "ROLE_" + Role.ADMIN.name();

  private final SiteAccessRepository access;
  private final UserRepository users;

  public SiteScopeService(SiteAccessRepository access, UserRepository users) {
    this.access = access;
    this.users = users;
  }

  @Override
  @SuppressWarnings("unchecked")
  public Optional<Set<UUID>> restriction() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null
        || !auth.isAuthenticated()
        || "anonymousUser".equals(auth.getName())
        || auth.getAuthorities().stream().anyMatch(a -> ADMIN.equals(a.getAuthority()))) {
      return Optional.empty();
    }
    RequestAttributes request = RequestContextHolder.getRequestAttributes();
    if (request != null
        && request.getAttribute(CACHE, RequestAttributes.SCOPE_REQUEST) instanceof Optional<?> c) {
      return (Optional<Set<UUID>>) c;
    }
    Optional<Set<UUID>> computed = asRestriction(access.findSiteIdsByUsername(auth.getName()));
    if (request != null) {
      request.setAttribute(CACHE, computed, RequestAttributes.SCOPE_REQUEST);
    }
    return computed;
  }

  @Override
  public Optional<Set<UUID>> restrictionOf(UUID userId) {
    boolean admin = users.findById(userId).map(u -> u.getRole() == Role.ADMIN).orElse(false);
    return admin ? Optional.empty() : asRestriction(access.findSiteIdsByUserId(userId));
  }

  /** Aucun site attribué : pas de restriction (comptes existants inchangés). */
  private static Optional<Set<UUID>> asRestriction(Set<UUID> sites) {
    return sites.isEmpty() ? Optional.empty() : Optional.of(Set.copyOf(sites));
  }
}

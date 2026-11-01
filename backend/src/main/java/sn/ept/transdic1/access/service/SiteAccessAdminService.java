package sn.ept.transdic1.access.service;

import java.time.Clock;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import sn.ept.transdic1.access.domain.SiteAccess;
import sn.ept.transdic1.access.dto.SiteScopeDtos.UserScope;
import sn.ept.transdic1.access.repository.SiteAccessRepository;
import sn.ept.transdic1.audit.service.AuditService;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.repository.SiteRepository;
import sn.ept.transdic1.security.domain.AppUser;
import sn.ept.transdic1.security.domain.Role;
import sn.ept.transdic1.security.repository.UserRepository;

/** Attribution des sites d'un compte par un administrateur (ADR 0018). */
@Service
public class SiteAccessAdminService {
  private final SiteAccessRepository access;
  private final UserRepository users;
  private final SiteRepository sites;
  private final AuditService audit;
  private final Clock clock;

  public SiteAccessAdminService(
      SiteAccessRepository access,
      UserRepository users,
      SiteRepository sites,
      AuditService audit,
      Clock clock) {
    this.access = access;
    this.users = users;
    this.sites = sites;
    this.audit = audit;
    this.clock = clock;
  }

  /** Comptes limités à certains sites ; les autres voient tout le parc. */
  @Transactional(readOnly = true)
  public List<UserScope> list() {
    return access.findAll().stream()
        .collect(
            Collectors.groupingBy(
                a -> a.getUser().getId(),
                Collectors.mapping(a -> a.getSite().getId(), Collectors.toList())))
        .entrySet()
        .stream()
        .map(e -> new UserScope(e.getKey(), e.getValue().stream().sorted().toList()))
        .sorted(Comparator.comparing(UserScope::userId))
        .toList();
  }

  /** Remplace les sites du compte ; une liste vide rend l'accès à tout le parc. */
  @Transactional
  public UserScope replace(UUID userId, List<UUID> siteIds) {
    AppUser user =
        users
            .findById(userId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Utilisateur introuvable"));
    var wanted = new LinkedHashSet<>(siteIds);
    if (user.getRole() == Role.ADMIN && !wanted.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Un administrateur voit toujours tout le parc.");
    }
    List<Site> found = sites.findAllById(wanted);
    if (found.size() != wanted.size()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Site inconnu");
    }
    access.deleteByUserId(userId);
    access.flush();
    var now = clock.instant();
    found.forEach(site -> access.save(new SiteAccess(user, site, now)));
    String codes =
        found.isEmpty()
            ? "tout le parc"
            : found.stream().map(Site::getCode).sorted().collect(Collectors.joining(","));
    audit.record("USER_SCOPE_UPDATED", "USER", userId, "sites=" + codes);
    return new UserScope(userId, found.stream().map(Site::getId).sorted().toList());
  }
}

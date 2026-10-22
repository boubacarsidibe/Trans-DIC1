package sn.ept.transdic1.access.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.security.domain.AppUser;

@Entity
@Table(name = "site_access")
public class SiteAccess {
  @Id private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private AppUser user;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "site_id", nullable = false)
  private Site site;

  @Column(name = "granted_at", nullable = false)
  private Instant grantedAt;

  protected SiteAccess() {}

  public SiteAccess(AppUser user, Site site, Instant grantedAt) {
    id = UUID.randomUUID();
    this.user = user;
    this.site = site;
    this.grantedAt = grantedAt;
  }

  public UUID getId() {
    return id;
  }

  public AppUser getUser() {
    return user;
  }

  public Site getSite() {
    return site;
  }

  public Instant getGrantedAt() {
    return grantedAt;
  }

  public void setGrantedAt(Instant grantedAt) {
    this.grantedAt = grantedAt;
  }
}

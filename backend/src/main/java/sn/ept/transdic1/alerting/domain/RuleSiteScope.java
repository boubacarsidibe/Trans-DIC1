package sn.ept.transdic1.alerting.domain;

import jakarta.persistence.*;
import java.util.UUID;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import sn.ept.transdic1.inventory.domain.Site;

@Entity
@Table(name = "rule_site_scope")
public class RuleSiteScope {
  @Id private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "rule_id", nullable = false)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private AlertRule rule;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "site_id", nullable = false)
  private Site site;

  @Column(name = "active", nullable = false)
  private boolean active;

  protected RuleSiteScope() {}

  public RuleSiteScope(AlertRule rule, Site site) {
    id = UUID.randomUUID();
    this.rule = rule;
    this.site = site;
    active = true;
  }

  public UUID getId() {
    return id;
  }

  public AlertRule getRule() {
    return rule;
  }

  public Site getSite() {
    return site;
  }

  public boolean isActive() {
    return active;
  }

  public void setActive(boolean active) {
    this.active = active;
  }
}

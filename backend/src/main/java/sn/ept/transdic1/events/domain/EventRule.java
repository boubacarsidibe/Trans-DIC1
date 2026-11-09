package sn.ept.transdic1.events.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import sn.ept.transdic1.alerting.domain.Severity;

/**
 * Règle d'événement (ADR 0021) : un événement qui satisfait tous les critères renseignés (source,
 * sévérité minimale, préfixe d'OID, expression sur le texte) ouvre une alerte EVENT ; un événement
 * qui satisfait la condition de retour la résout. Sans site ni équipement, la règle vaut pour tous
 * les équipements.
 */
@Entity
@Table(name = "event_rule")
public class EventRule {
  @Id private UUID id;

  @Column(name = "name", nullable = false, unique = true, length = 120)
  private String name;

  @Enumerated(EnumType.STRING)
  @Column(name = "source", length = 16)
  private EventSource source;

  @Enumerated(EnumType.STRING)
  @Column(name = "min_severity", length = 16)
  private EventSeverity minSeverity;

  @Column(name = "oid_prefix", length = 255)
  private String oidPrefix;

  @Column(name = "message_regex", length = 200)
  private String messageRegex;

  @Column(name = "resolve_oid", length = 255)
  private String resolveOid;

  @Column(name = "resolve_regex", length = 200)
  private String resolveRegex;

  @Enumerated(EnumType.STRING)
  @Column(name = "alert_severity", nullable = false, length = 16)
  private Severity alertSeverity;

  @Column(name = "dedup_window_seconds", nullable = false)
  private int dedupWindowSeconds = 300;

  @Column(name = "active", nullable = false)
  private boolean active;

  @ElementCollection(fetch = FetchType.EAGER)
  @CollectionTable(name = "event_rule_site", joinColumns = @JoinColumn(name = "rule_id"))
  @Column(name = "site_id", nullable = false)
  private Set<UUID> siteIds = new HashSet<>();

  @ElementCollection(fetch = FetchType.EAGER)
  @CollectionTable(name = "event_rule_equipment", joinColumns = @JoinColumn(name = "rule_id"))
  @Column(name = "equipment_id", nullable = false)
  private Set<UUID> equipmentIds = new HashSet<>();

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected EventRule() {}

  public EventRule(String name, Severity alertSeverity) {
    id = UUID.randomUUID();
    this.name = name;
    this.alertSeverity = alertSeverity;
    createdAt = Instant.now();
    updatedAt = createdAt;
  }

  @PreUpdate
  void touch() {
    updatedAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public EventSource getSource() {
    return source;
  }

  public void setSource(EventSource source) {
    this.source = source;
  }

  public EventSeverity getMinSeverity() {
    return minSeverity;
  }

  public void setMinSeverity(EventSeverity minSeverity) {
    this.minSeverity = minSeverity;
  }

  public String getOidPrefix() {
    return oidPrefix;
  }

  public void setOidPrefix(String oidPrefix) {
    this.oidPrefix = oidPrefix;
  }

  public String getMessageRegex() {
    return messageRegex;
  }

  public void setMessageRegex(String messageRegex) {
    this.messageRegex = messageRegex;
  }

  public String getResolveOid() {
    return resolveOid;
  }

  public void setResolveOid(String resolveOid) {
    this.resolveOid = resolveOid;
  }

  public String getResolveRegex() {
    return resolveRegex;
  }

  public void setResolveRegex(String resolveRegex) {
    this.resolveRegex = resolveRegex;
  }

  public Severity getAlertSeverity() {
    return alertSeverity;
  }

  public void setAlertSeverity(Severity alertSeverity) {
    this.alertSeverity = alertSeverity;
  }

  public int getDedupWindowSeconds() {
    return dedupWindowSeconds;
  }

  public void setDedupWindowSeconds(int dedupWindowSeconds) {
    this.dedupWindowSeconds = dedupWindowSeconds;
  }

  public boolean isActive() {
    return active;
  }

  public void setActive(boolean active) {
    this.active = active;
  }

  public Set<UUID> getSiteIds() {
    return siteIds;
  }

  public void setSiteIds(Set<UUID> siteIds) {
    this.siteIds.clear();
    this.siteIds.addAll(siteIds);
  }

  public Set<UUID> getEquipmentIds() {
    return equipmentIds;
  }

  public void setEquipmentIds(Set<UUID> equipmentIds) {
    this.equipmentIds.clear();
    this.equipmentIds.addAll(equipmentIds);
  }

  /** Une condition de retour (résolution automatique) est définie. */
  public boolean hasResolveCondition() {
    return resolveOid != null || resolveRegex != null;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}

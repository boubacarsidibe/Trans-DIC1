package sn.ept.transdic1.inventory.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import sn.ept.transdic1.common.persistence.DurationSecondsConverter;

@Entity
@Table(name = "collection_configuration")
public class CollectionConfiguration {
  @Id private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "equipment_id", nullable = false)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private Equipment equipment;

  @Column(name = "name", nullable = false, length = 120)
  private String name;

  @Enumerated(EnumType.STRING)
  @Column(name = "mode", nullable = false)
  private CollectionMode mode;

  @Convert(converter = DurationSecondsConverter.class)
  @Column(name = "poll_interval_seconds", nullable = false)
  private Duration pollInterval;

  @Convert(converter = DurationSecondsConverter.class)
  @Column(name = "timeout_seconds", nullable = false)
  private Duration timeout;

  @Column(name = "retry_count", nullable = false)
  private Integer retryCount;

  @Enumerated(EnumType.STRING)
  @Column(name = "snmp_version")
  private SnmpVersion snmpVersion;

  @JsonIgnore
  @Column(name = "community_reference", length = 255)
  private String communityReference;

  @JsonIgnore
  @Column(name = "security_identity", length = 255)
  private String securityIdentity;

  @JsonIgnore
  @Column(name = "secret_reference", length = 255)
  private String secretReference;

  @Column(name = "active", nullable = false)
  private boolean active;

  @Column(name = "last_run_at")
  private Instant lastRunAt;

  @Column(name = "next_run_at")
  private Instant nextRunAt;

  protected CollectionConfiguration() {}

  public CollectionConfiguration(
      Equipment equipment,
      String name,
      CollectionMode mode,
      Duration pollInterval,
      Duration timeout,
      Integer retryCount) {
    id = UUID.randomUUID();
    this.equipment = equipment;
    this.name = name;
    this.mode = mode;
    this.pollInterval = pollInterval;
    this.timeout = timeout;
    this.retryCount = retryCount;
    active = true;
  }

  public UUID getId() {
    return id;
  }

  public Equipment getEquipment() {
    return equipment;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public CollectionMode getMode() {
    return mode;
  }

  public void setMode(CollectionMode mode) {
    this.mode = mode;
  }

  public Duration getPollInterval() {
    return pollInterval;
  }

  public void setPollInterval(Duration pollInterval) {
    this.pollInterval = pollInterval;
  }

  public Duration getTimeout() {
    return timeout;
  }

  public void setTimeout(Duration timeout) {
    this.timeout = timeout;
  }

  public Integer getRetryCount() {
    return retryCount;
  }

  public void setRetryCount(Integer retryCount) {
    this.retryCount = retryCount;
  }

  public SnmpVersion getSnmpVersion() {
    return snmpVersion;
  }

  public void setSnmpVersion(SnmpVersion snmpVersion) {
    this.snmpVersion = snmpVersion;
  }

  public String getCommunityReference() {
    return communityReference;
  }

  public void setCommunityReference(String communityReference) {
    this.communityReference = communityReference;
  }

  public String getSecurityIdentity() {
    return securityIdentity;
  }

  public void setSecurityIdentity(String securityIdentity) {
    this.securityIdentity = securityIdentity;
  }

  public String getSecretReference() {
    return secretReference;
  }

  public void setSecretReference(String secretReference) {
    this.secretReference = secretReference;
  }

  public boolean isActive() {
    return active;
  }

  public void setActive(boolean active) {
    this.active = active;
  }

  public Instant getLastRunAt() {
    return lastRunAt;
  }

  public void setLastRunAt(Instant lastRunAt) {
    this.lastRunAt = lastRunAt;
  }

  public Instant getNextRunAt() {
    return nextRunAt;
  }

  public void setNextRunAt(Instant nextRunAt) {
    this.nextRunAt = nextRunAt;
  }
}

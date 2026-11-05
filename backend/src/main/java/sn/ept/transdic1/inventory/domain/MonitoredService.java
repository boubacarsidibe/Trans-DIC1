package sn.ept.transdic1.inventory.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * Service supervisé d'un équipement (sondes sans agent comprises). {@code @DynamicUpdate} : le
 * dépôt d'un résultat de sonde n'écrit que les colonnes changées (dernier résultat, compteur) et ne
 * réécrit pas, avec une valeur périmée, une modification faite entre-temps par un administrateur.
 */
@Entity
@DynamicUpdate
@Table(name = "monitored_service")
public class MonitoredService {
  @Id private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "equipment_id", nullable = false)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private Equipment equipment;

  @Column(name = "name", nullable = false, length = 120)
  private String name;

  @Enumerated(EnumType.STRING)
  @Column(name = "type", nullable = false)
  private ServiceType type;

  @Column(name = "protocol", length = 32)
  private String protocol;

  @Column(name = "port")
  private Integer port;

  @Column(name = "access_point", length = 255)
  private String accessPoint;

  @Column(name = "active", nullable = false)
  private boolean active;

  @Column(name = "interval_seconds", nullable = false)
  private int intervalSeconds = 60;

  @Column(name = "timeout_ms", nullable = false)
  private int timeoutMs = 5000;

  @Column(name = "expected_status", length = 16)
  private String expectedStatus;

  @Column(name = "expected_text", length = 200)
  private String expectedText;

  @Column(name = "next_run_at")
  private Instant nextRunAt;

  @Column(name = "last_checked_at")
  private Instant lastCheckedAt;

  @Column(name = "last_up")
  private Boolean lastUp;

  @Column(name = "last_latency_ms")
  private Integer lastLatencyMs;

  @Column(name = "last_error", length = 200)
  private String lastError;

  @Column(name = "consecutive_failures", nullable = false)
  private int consecutiveFailures;

  protected MonitoredService() {}

  public MonitoredService(Equipment equipment, String name, ServiceType type) {
    id = UUID.randomUUID();
    this.equipment = equipment;
    this.name = name;
    this.type = type;
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

  public ServiceType getType() {
    return type;
  }

  public void setType(ServiceType type) {
    this.type = type;
  }

  public String getProtocol() {
    return protocol;
  }

  public void setProtocol(String protocol) {
    this.protocol = protocol;
  }

  public Integer getPort() {
    return port;
  }

  public void setPort(Integer port) {
    this.port = port;
  }

  public String getAccessPoint() {
    return accessPoint;
  }

  public void setAccessPoint(String accessPoint) {
    this.accessPoint = accessPoint;
  }

  public boolean isActive() {
    return active;
  }

  public void setActive(boolean active) {
    this.active = active;
  }

  public int getIntervalSeconds() {
    return intervalSeconds;
  }

  public void setIntervalSeconds(int intervalSeconds) {
    this.intervalSeconds = intervalSeconds;
  }

  public int getTimeoutMs() {
    return timeoutMs;
  }

  public void setTimeoutMs(int timeoutMs) {
    this.timeoutMs = timeoutMs;
  }

  public String getExpectedStatus() {
    return expectedStatus;
  }

  public void setExpectedStatus(String expectedStatus) {
    this.expectedStatus = expectedStatus;
  }

  public String getExpectedText() {
    return expectedText;
  }

  public void setExpectedText(String expectedText) {
    this.expectedText = expectedText;
  }

  public Instant getNextRunAt() {
    return nextRunAt;
  }

  public void setNextRunAt(Instant nextRunAt) {
    this.nextRunAt = nextRunAt;
  }

  public Instant getLastCheckedAt() {
    return lastCheckedAt;
  }

  public void setLastCheckedAt(Instant lastCheckedAt) {
    this.lastCheckedAt = lastCheckedAt;
  }

  public Boolean getLastUp() {
    return lastUp;
  }

  public void setLastUp(Boolean lastUp) {
    this.lastUp = lastUp;
  }

  public Integer getLastLatencyMs() {
    return lastLatencyMs;
  }

  public void setLastLatencyMs(Integer lastLatencyMs) {
    this.lastLatencyMs = lastLatencyMs;
  }

  public String getLastError() {
    return lastError;
  }

  public void setLastError(String lastError) {
    this.lastError = lastError;
  }

  public int getConsecutiveFailures() {
    return consecutiveFailures;
  }

  public void setConsecutiveFailures(int consecutiveFailures) {
    this.consecutiveFailures = consecutiveFailures;
  }
}

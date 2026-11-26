package sn.ept.transdic1.inventory.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Une machine qui s'est présentée avec un jeton d'inscription (ADR 0054), inscrite ou en attente.
 * Une seule ligne par machine et par site : c'est ce qui rend l'inscription idempotente.
 */
@Entity
@Table(name = "agent_enrollment")
public class AgentEnrollment {
  @Id private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "site_id", nullable = false)
  private Site site;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "token_id")
  private EnrollmentToken token;

  @Column(name = "machine_id", nullable = false, length = 128)
  private String machineId;

  @Column(nullable = false, length = 120)
  private String hostname;

  /** Adresses déclarées par l'agent, séparées par des virgules. */
  @Column(name = "ip_addresses", length = 400)
  private String ipAddresses;

  /** Adresse vue par le serveur (connexion TCP). */
  @Column(name = "source_address", length = 45)
  private String sourceAddress;

  @Column(name = "os_name", length = 64)
  private String osName;

  @Column(name = "os_version", length = 120)
  private String osVersion;

  @Column(name = "agent_version", length = 32)
  private String agentVersion;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private EnrollmentStatus status;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "equipment_id")
  private Equipment equipment;

  @Column(name = "requested_at", nullable = false)
  private Instant requestedAt;

  @Column(name = "last_seen_at", nullable = false)
  private Instant lastSeenAt;

  @Column(name = "decided_by", length = 64)
  private String decidedBy;

  @Column(name = "decided_at")
  private Instant decidedAt;

  @Column(name = "enrolled_at")
  private Instant enrolledAt;

  protected AgentEnrollment() {}

  public AgentEnrollment(
      Site site, EnrollmentToken token, String machineId, EnrollmentStatus status, Instant now) {
    id = UUID.randomUUID();
    this.site = site;
    this.token = token;
    this.machineId = machineId;
    this.status = status;
    requestedAt = now;
    lastSeenAt = now;
    hostname = "";
  }

  /** Met à jour ce que la machine déclare d'elle-même, à chaque demande. */
  public void describe(
      String hostname,
      String ipAddresses,
      String sourceAddress,
      String osName,
      String osVersion,
      String agentVersion,
      Instant now) {
    this.hostname = hostname;
    this.ipAddresses = ipAddresses;
    this.sourceAddress = sourceAddress;
    this.osName = osName;
    this.osVersion = osVersion;
    this.agentVersion = agentVersion;
    lastSeenAt = now;
  }

  /** Nouvelle demande d'une machine déjà connue : elle repasse par le jeton présenté. */
  public void requestAgain(EnrollmentToken token, EnrollmentStatus status, Instant now) {
    this.token = token;
    this.status = status;
    requestedAt = now;
    decidedBy = null;
    decidedAt = null;
  }

  public void approve(Equipment equipment, String by, Instant now) {
    this.equipment = equipment;
    status = EnrollmentStatus.APPROVED;
    decidedBy = by;
    decidedAt = now;
  }

  public void reject(String by, Instant now) {
    status = EnrollmentStatus.REJECTED;
    decidedBy = by;
    decidedAt = now;
  }

  public void enrolled(Equipment equipment, Instant now) {
    this.equipment = equipment;
    status = EnrollmentStatus.ENROLLED;
    enrolledAt = now;
  }

  public UUID getId() {
    return id;
  }

  public Site getSite() {
    return site;
  }

  public EnrollmentToken getToken() {
    return token;
  }

  public String getMachineId() {
    return machineId;
  }

  public String getHostname() {
    return hostname;
  }

  public String getIpAddresses() {
    return ipAddresses;
  }

  public String getSourceAddress() {
    return sourceAddress;
  }

  public String getOsName() {
    return osName;
  }

  public String getOsVersion() {
    return osVersion;
  }

  public String getAgentVersion() {
    return agentVersion;
  }

  public EnrollmentStatus getStatus() {
    return status;
  }

  public Equipment getEquipment() {
    return equipment;
  }

  public Instant getRequestedAt() {
    return requestedAt;
  }

  public Instant getLastSeenAt() {
    return lastSeenAt;
  }

  public String getDecidedBy() {
    return decidedBy;
  }

  public Instant getDecidedAt() {
    return decidedAt;
  }

  public Instant getEnrolledAt() {
    return enrolledAt;
  }
}

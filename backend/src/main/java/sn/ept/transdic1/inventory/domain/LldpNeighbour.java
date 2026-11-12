package sn.ept.transdic1.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Ce qu'un équipement dit voir au bout d'un de ses ports, d'après LLDP (ADR 0034).
 *
 * <p>C'est une <b>observation</b>, pas une vérité : l'équipement d'en face s'annonce lui-même, et
 * rien ne garantit que son nom corresponde à une fiche de l'inventaire. Quand la plateforme
 * reconnaît l'équipement annoncé, elle le note dans {@code matchedEquipment} avec la raison ; la
 * topologie, elle, n'est modifiée que par un administrateur.
 */
@Entity
@Table(name = "lldp_neighbour")
public class LldpNeighbour {
  @Id private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "equipment_id", nullable = false)
  private Equipment equipment;

  @Column(name = "local_port_index", nullable = false)
  private int localPortIndex;

  @Column(name = "local_port", length = 120)
  private String localPort;

  @Column(name = "remote_chassis_id", nullable = false, length = 120)
  private String remoteChassisId;

  @Column(name = "remote_port_id", nullable = false, length = 120)
  private String remotePortId;

  @Column(name = "remote_port_description", length = 255)
  private String remotePortDescription;

  @Column(name = "remote_system_name", length = 255)
  private String remoteSystemName;

  @Column(name = "remote_system_description", length = 500)
  private String remoteSystemDescription;

  @Column(name = "remote_management_address", length = 45)
  private String remoteManagementAddress;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "matched_equipment_id")
  private Equipment matchedEquipment;

  @Column(name = "match_reason", length = 32)
  private String matchReason;

  @Column(name = "ignored", nullable = false)
  private boolean ignored;

  @Column(name = "first_seen_at", nullable = false)
  private Instant firstSeenAt;

  @Column(name = "last_seen_at", nullable = false)
  private Instant lastSeenAt;

  protected LldpNeighbour() {}

  public LldpNeighbour(
      Equipment equipment,
      int localPortIndex,
      String remoteChassisId,
      String remotePortId,
      Instant seenAt) {
    this.id = UUID.randomUUID();
    this.equipment = equipment;
    this.localPortIndex = localPortIndex;
    this.remoteChassisId = remoteChassisId;
    this.remotePortId = remotePortId;
    this.firstSeenAt = seenAt;
    this.lastSeenAt = seenAt;
  }

  public UUID getId() {
    return id;
  }

  public Equipment getEquipment() {
    return equipment;
  }

  public int getLocalPortIndex() {
    return localPortIndex;
  }

  public String getLocalPort() {
    return localPort;
  }

  public void setLocalPort(String localPort) {
    this.localPort = localPort;
  }

  public String getRemoteChassisId() {
    return remoteChassisId;
  }

  public String getRemotePortId() {
    return remotePortId;
  }

  public String getRemotePortDescription() {
    return remotePortDescription;
  }

  public void setRemotePortDescription(String remotePortDescription) {
    this.remotePortDescription = remotePortDescription;
  }

  public String getRemoteSystemName() {
    return remoteSystemName;
  }

  public void setRemoteSystemName(String remoteSystemName) {
    this.remoteSystemName = remoteSystemName;
  }

  public String getRemoteSystemDescription() {
    return remoteSystemDescription;
  }

  public void setRemoteSystemDescription(String remoteSystemDescription) {
    this.remoteSystemDescription = remoteSystemDescription;
  }

  public String getRemoteManagementAddress() {
    return remoteManagementAddress;
  }

  public void setRemoteManagementAddress(String remoteManagementAddress) {
    this.remoteManagementAddress = remoteManagementAddress;
  }

  public Equipment getMatchedEquipment() {
    return matchedEquipment;
  }

  public void setMatchedEquipment(Equipment matchedEquipment) {
    this.matchedEquipment = matchedEquipment;
  }

  public String getMatchReason() {
    return matchReason;
  }

  public void setMatchReason(String matchReason) {
    this.matchReason = matchReason;
  }

  public boolean isIgnored() {
    return ignored;
  }

  public void setIgnored(boolean ignored) {
    this.ignored = ignored;
  }

  public Instant getFirstSeenAt() {
    return firstSeenAt;
  }

  public Instant getLastSeenAt() {
    return lastSeenAt;
  }

  public void setLastSeenAt(Instant lastSeenAt) {
    this.lastSeenAt = lastSeenAt;
  }
}

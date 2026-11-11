package sn.ept.transdic1.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigInteger;
import java.time.Instant;
import java.util.UUID;

/**
 * Interface réseau d'un équipement, découverte par SNMP (IF-MIB). Les compteurs du dernier passage
 * sont conservés ici : sans eux, impossible de transformer des compteurs cumulés en débit.
 */
@Entity
@Table(name = "equipment_interface")
public class EquipmentInterface {
  @Id private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "equipment_id", nullable = false)
  private Equipment equipment;

  @Column(name = "if_index", nullable = false)
  private int ifIndex;

  @Column(name = "name", nullable = false, length = 120)
  private String name;

  @Column(name = "alias", length = 255)
  private String alias;

  @Column(name = "if_type")
  private Integer ifType;

  /** Capacité annoncée du lien, en bits par seconde. */
  @Column(name = "speed_bps")
  private Long speedBps;

  @Column(name = "admin_up")
  private Boolean adminUp;

  @Column(name = "oper_up")
  private Boolean operUp;

  /** Une interface non suivie reste à l'inventaire mais ne produit aucune mesure. */
  @Column(name = "monitored", nullable = false)
  private boolean monitored = true;

  @Column(name = "in_octets", precision = 24)
  private BigInteger inOctets;

  @Column(name = "out_octets", precision = 24)
  private BigInteger outOctets;

  @Column(name = "in_errors", precision = 24)
  private BigInteger inErrors;

  @Column(name = "out_errors", precision = 24)
  private BigInteger outErrors;

  @Column(name = "in_discards", precision = 24)
  private BigInteger inDiscards;

  @Column(name = "out_discards", precision = 24)
  private BigInteger outDiscards;

  @Column(name = "counters_at")
  private Instant countersAt;

  @Column(name = "discovered_at", nullable = false)
  private Instant discoveredAt;

  /** Dernier passage de découverte qui a vu cette interface dans la table de l'équipement. */
  @Column(name = "last_seen_at", nullable = false)
  private Instant lastSeenAt;

  protected EquipmentInterface() {}

  public EquipmentInterface(Equipment equipment, int ifIndex, String name, Instant at) {
    this.id = UUID.randomUUID();
    this.equipment = equipment;
    this.ifIndex = ifIndex;
    this.name = name;
    this.discoveredAt = at;
    this.lastSeenAt = at;
  }

  /** Remplace les compteurs gardés pour le prochain calcul de débit. */
  public void rememberCounters(
      BigInteger inOctets,
      BigInteger outOctets,
      BigInteger inErrors,
      BigInteger outErrors,
      BigInteger inDiscards,
      BigInteger outDiscards,
      Instant at) {
    this.inOctets = inOctets;
    this.outOctets = outOctets;
    this.inErrors = inErrors;
    this.outErrors = outErrors;
    this.inDiscards = inDiscards;
    this.outDiscards = outDiscards;
    this.countersAt = at;
  }

  public UUID getId() {
    return id;
  }

  public Equipment getEquipment() {
    return equipment;
  }

  public int getIfIndex() {
    return ifIndex;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getAlias() {
    return alias;
  }

  public void setAlias(String alias) {
    this.alias = alias;
  }

  public Integer getIfType() {
    return ifType;
  }

  public void setIfType(Integer ifType) {
    this.ifType = ifType;
  }

  public Long getSpeedBps() {
    return speedBps;
  }

  public void setSpeedBps(Long speedBps) {
    this.speedBps = speedBps;
  }

  public Boolean getAdminUp() {
    return adminUp;
  }

  public void setAdminUp(Boolean adminUp) {
    this.adminUp = adminUp;
  }

  public Boolean getOperUp() {
    return operUp;
  }

  public void setOperUp(Boolean operUp) {
    this.operUp = operUp;
  }

  public boolean isMonitored() {
    return monitored;
  }

  public void setMonitored(boolean monitored) {
    this.monitored = monitored;
  }

  public BigInteger getInOctets() {
    return inOctets;
  }

  public BigInteger getOutOctets() {
    return outOctets;
  }

  public BigInteger getInErrors() {
    return inErrors;
  }

  public BigInteger getOutErrors() {
    return outErrors;
  }

  public BigInteger getInDiscards() {
    return inDiscards;
  }

  public BigInteger getOutDiscards() {
    return outDiscards;
  }

  public Instant getCountersAt() {
    return countersAt;
  }

  public Instant getDiscoveredAt() {
    return discoveredAt;
  }

  public Instant getLastSeenAt() {
    return lastSeenAt;
  }

  public void setLastSeenAt(Instant lastSeenAt) {
    this.lastSeenAt = lastSeenAt;
  }
}

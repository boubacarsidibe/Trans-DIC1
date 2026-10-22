package sn.ept.transdic1.inventory.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
public class Equipment {
  @Id private UUID id;

  @Column(name = "inventory_code", nullable = false, unique = true)
  private String inventoryCode;

  @Column(nullable = false)
  private String name;

  @Column(name = "management_address", nullable = false, unique = true)
  private String managementAddress;

  @Enumerated(EnumType.STRING)
  @Column(name = "equipment_type", nullable = false)
  private EquipmentType type;

  @Enumerated(EnumType.STRING)
  @Column(name = "operational_status", nullable = false)
  private EquipmentStatus status;

  @Column(name = "is_fictitious", nullable = false)
  private boolean fictitious;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "site_id")
  private Site site;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected Equipment() {}

  public Equipment(
      String code,
      String name,
      String address,
      EquipmentType type,
      EquipmentStatus status,
      Site site) {
    id = UUID.randomUUID();
    inventoryCode = code;
    this.name = name;
    managementAddress = address;
    this.type = type;
    this.status = status;
    this.site = site;
    createdAt = Instant.now();
  }

  public void update(
      String name, String address, EquipmentType type, EquipmentStatus status, Site site) {
    this.name = name;
    managementAddress = address;
    this.type = type;
    this.status = status;
    this.site = site;
  }

  public UUID getId() {
    return id;
  }

  public String getInventoryCode() {
    return inventoryCode;
  }

  public String getName() {
    return name;
  }

  public String getManagementAddress() {
    return managementAddress;
  }

  public EquipmentType getType() {
    return type;
  }

  public EquipmentStatus getStatus() {
    return status;
  }

  public Site getSite() {
    return site;
  }
}

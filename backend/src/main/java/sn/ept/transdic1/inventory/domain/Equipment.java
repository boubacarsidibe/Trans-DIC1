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

  @Column(length = 120)
  private String manufacturer;

  @Column(length = 120)
  private String model;

  @Column(name = "operating_system", length = 120)
  private String operatingSystem;

  @Column(name = "monitoring_enabled", nullable = false)
  private boolean monitoringEnabled = true;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

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
    updatedAt = createdAt;
  }

  @PreUpdate
  void touch() {
    updatedAt = Instant.now();
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

  public String getManufacturer() {
    return manufacturer;
  }

  public void setManufacturer(String manufacturer) {
    this.manufacturer = manufacturer;
  }

  public String getModel() {
    return model;
  }

  public void setModel(String model) {
    this.model = model;
  }

  public String getOperatingSystem() {
    return operatingSystem;
  }

  public void setOperatingSystem(String operatingSystem) {
    this.operatingSystem = operatingSystem;
  }

  public boolean isMonitoringEnabled() {
    return monitoringEnabled;
  }

  public void setMonitoringEnabled(boolean monitoringEnabled) {
    this.monitoringEnabled = monitoringEnabled;
  }

  public boolean isFictitious() {
    return fictitious;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}

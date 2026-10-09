package sn.ept.transdic1.inventory.dto;

import java.util.UUID;
import sn.ept.transdic1.inventory.domain.*;

public record EquipmentResponse(
    UUID id,
    String inventoryCode,
    String name,
    String managementAddress,
    EquipmentType type,
    EquipmentStatus status,
    UUID siteId,
    String site) {
  public static EquipmentResponse from(Equipment e) {
    return new EquipmentResponse(
        e.getId(),
        e.getInventoryCode(),
        e.getName(),
        e.getManagementAddress(),
        e.getType(),
        e.getStatus(),
        e.getSite().getId(),
        e.getSite().getName());
  }
}

package sn.ept.transdic1.inventory.dto;

import jakarta.validation.constraints.*;
import java.util.UUID;
import sn.ept.transdic1.inventory.domain.*;

public record EquipmentRequest(
    @NotBlank @Size(max = 64) String inventoryCode,
    @NotBlank @Size(max = 120) String name,
    @NotBlank @Size(max = 45) String managementAddress,
    @NotNull EquipmentType type,
    @NotNull EquipmentStatus status,
    @NotNull UUID siteId) {}

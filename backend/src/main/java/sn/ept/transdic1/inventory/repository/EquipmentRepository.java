package sn.ept.transdic1.inventory.repository;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import sn.ept.transdic1.inventory.domain.Equipment;

public interface EquipmentRepository extends JpaRepository<Equipment, UUID> {
  boolean existsByInventoryCodeOrManagementAddress(String code, String address);
}

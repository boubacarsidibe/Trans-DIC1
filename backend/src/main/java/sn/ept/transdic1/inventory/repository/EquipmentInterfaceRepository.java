package sn.ept.transdic1.inventory.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import sn.ept.transdic1.inventory.domain.EquipmentInterface;

public interface EquipmentInterfaceRepository extends JpaRepository<EquipmentInterface, UUID> {
  List<EquipmentInterface> findByEquipmentIdOrderByIfIndex(UUID equipmentId);
}

package sn.ept.transdic1.inventory.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import sn.ept.transdic1.inventory.domain.Equipment;

public interface EquipmentRepository
    extends JpaRepository<Equipment, UUID>, JpaSpecificationExecutor<Equipment> {
  boolean existsByInventoryCodeOrManagementAddress(String code, String address);

  boolean existsByManagementAddressAndIdNot(String address, UUID id);

  long countBySiteId(UUID siteId);

  /** Équipements à superviser : entrée du planificateur de collecte. */
  List<Equipment> findByMonitoringEnabledTrue();
}

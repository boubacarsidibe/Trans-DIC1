package sn.ept.transdic1.access.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import sn.ept.transdic1.access.domain.*;

public interface EquipmentAccessRepository extends JpaRepository<EquipmentAccess, UUID> {
  List<EquipmentAccess> findByUserId(UUID userId);

  boolean existsByUserIdAndEquipmentId(UUID userId, UUID equipmentId);
}

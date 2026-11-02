package sn.ept.transdic1.inventory.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import sn.ept.transdic1.inventory.domain.*;

public interface MonitoredServiceRepository extends JpaRepository<MonitoredService, UUID> {
  List<MonitoredService> findByEquipmentIdAndActiveTrue(UUID equipmentId);

  List<MonitoredService> findByActiveTrueAndNextRunAtLessThanEqual(Instant now);

  List<MonitoredService> findByEquipmentIdOrderByName(UUID equipmentId);

  long countByEquipmentIdAndActiveTrue(UUID equipmentId);
}

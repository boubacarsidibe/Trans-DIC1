package sn.ept.transdic1.inventory.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import sn.ept.transdic1.inventory.domain.EquipmentTemplateApplication;

public interface EquipmentTemplateRepository
    extends JpaRepository<EquipmentTemplateApplication, UUID> {
  List<EquipmentTemplateApplication> findByEquipmentIdOrderByAppliedAtDesc(UUID equipmentId);

  Optional<EquipmentTemplateApplication> findByEquipmentIdAndTemplateId(
      UUID equipmentId, String templateId);
}

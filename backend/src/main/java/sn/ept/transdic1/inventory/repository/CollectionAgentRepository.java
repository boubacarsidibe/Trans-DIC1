package sn.ept.transdic1.inventory.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import sn.ept.transdic1.inventory.domain.*;

public interface CollectionAgentRepository extends JpaRepository<CollectionAgent, UUID> {
  Optional<CollectionAgent> findByIdentifier(String identifier);

  Optional<CollectionAgent> findByEquipmentIdAndStatus(UUID equipmentId, AgentStatus status);
}

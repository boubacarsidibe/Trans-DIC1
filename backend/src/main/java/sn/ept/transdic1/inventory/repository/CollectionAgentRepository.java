package sn.ept.transdic1.inventory.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import sn.ept.transdic1.inventory.domain.*;

public interface CollectionAgentRepository extends JpaRepository<CollectionAgent, UUID> {
  Optional<CollectionAgent> findByIdentifier(String identifier);

  long countByStatus(AgentStatus status);

  Optional<CollectionAgent> findByEquipmentIdAndStatus(UUID equipmentId, AgentStatus status);

  /** Agent actif présentant ce jeton (par empreinte) : authentification des envois de mesures. */
  Optional<CollectionAgent> findByTokenHashAndStatus(String tokenHash, AgentStatus status);
}

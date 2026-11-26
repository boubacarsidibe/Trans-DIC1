package sn.ept.transdic1.inventory.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import sn.ept.transdic1.inventory.domain.AgentEnrollment;
import sn.ept.transdic1.inventory.domain.EnrollmentStatus;

public interface AgentEnrollmentRepository extends JpaRepository<AgentEnrollment, UUID> {
  Optional<AgentEnrollment> findBySiteIdAndMachineId(UUID siteId, String machineId);

  @Query(
      "select e from AgentEnrollment e join fetch e.site left join fetch e.token"
          + " left join fetch e.equipment where e.status in :statuses order by e.requestedAt asc")
  List<AgentEnrollment> findByStatusIn(@Param("statuses") Collection<EnrollmentStatus> statuses);

  /** Équipement déjà rattaché à une machine inscrite : on ne l'adopte pas une seconde fois. */
  boolean existsByEquipmentId(UUID equipmentId);
}

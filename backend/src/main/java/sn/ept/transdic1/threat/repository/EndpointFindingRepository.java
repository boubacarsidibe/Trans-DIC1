package sn.ept.transdic1.threat.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import sn.ept.transdic1.threat.domain.EndpointFinding;

public interface EndpointFindingRepository extends JpaRepository<EndpointFinding, UUID> {
  Optional<EndpointFinding> findByEquipmentIdAndRuleAndSubject(
      UUID equipmentId, String rule, String subject);

  List<EndpointFinding> findTop300ByOrderByDetectedAtDesc();
}

package sn.ept.transdic1.remediation.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import sn.ept.transdic1.remediation.domain.RemediationJob;
import sn.ept.transdic1.remediation.domain.RemediationState;

public interface RemediationJobRepository extends JpaRepository<RemediationJob, UUID> {
  List<RemediationJob> findByAlertIdOrderByCreatedAtAsc(UUID alertId);

  List<RemediationJob> findTop50ByEquipmentIdOrderByCreatedAtDesc(UUID equipmentId);

  List<RemediationJob> findByAgentIdAndStateOrderByCreatedAtAsc(
      UUID agentId, RemediationState state);

  Optional<RemediationJob> findByIdAndAgentId(UUID id, UUID agentId);

  boolean existsByEquipmentIdAndActionNameAndStateIn(
      UUID equipmentId, String actionName, Collection<RemediationState> states);

  /** Une exécution de cette règle sur cet équipement depuis l'instant donné (délai de grâce). */
  boolean existsByRuleIdAndEquipmentIdAndCreatedAtAfter(
      UUID ruleId, UUID equipmentId, Instant since);

  List<RemediationJob> findByStateInAndExpiresAtBefore(
      Collection<RemediationState> states, Instant now);
}

package sn.ept.transdic1.oncall.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import sn.ept.transdic1.oncall.domain.OnCallLevel;
import sn.ept.transdic1.oncall.domain.OnCallRotation;

public interface OnCallRotationRepository extends JpaRepository<OnCallRotation, UUID> {
  @EntityGraph(attributePaths = "members")
  List<OnCallRotation> findByScheduleIdOrderByLevelAsc(UUID scheduleId);

  @EntityGraph(attributePaths = "members")
  Optional<OnCallRotation> findByScheduleIdAndLevel(UUID scheduleId, OnCallLevel level);

  @EntityGraph(attributePaths = {"members", "schedule"})
  List<OnCallRotation> findByScheduleActiveTrue();
}

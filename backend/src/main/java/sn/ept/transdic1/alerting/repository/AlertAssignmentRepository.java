package sn.ept.transdic1.alerting.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import sn.ept.transdic1.alerting.domain.*;

public interface AlertAssignmentRepository extends JpaRepository<AlertAssignment, UUID> {
  Optional<AlertAssignment> findByAlertIdAndEndedAtIsNull(UUID alertId);
}

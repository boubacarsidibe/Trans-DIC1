package sn.ept.transdic1.alerting.repository;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import sn.ept.transdic1.alerting.domain.*;

public interface AlertAssignmentRepository extends JpaRepository<AlertAssignment, UUID> {
  /** Assignations en cours d'une alerte, de la plus ancienne à la plus récente. */
  java.util.List<AlertAssignment> findAllByAlertIdAndEndedAtIsNullOrderByAssignedAtAsc(
      UUID alertId);
}

package sn.ept.transdic1.alerting.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import sn.ept.transdic1.alerting.domain.IncidentAlert;

public interface IncidentAlertRepository extends JpaRepository<IncidentAlert, UUID> {

  Optional<IncidentAlert> findByAlertId(UUID alertId);

  List<IncidentAlert> findByIncidentIdOrderByJoinedAt(UUID incidentId);

  long countByIncidentId(UUID incidentId);
}

package sn.ept.transdic1.alerting.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import sn.ept.transdic1.alerting.domain.MaintenanceDemand;

public interface MaintenanceDemandRepository extends JpaRepository<MaintenanceDemand, UUID> {
  List<MaintenanceDemand> findTop200ByOrderByCreatedAtDesc();

  List<MaintenanceDemand> findTop200ByRequesterIdOrderByCreatedAtDesc(UUID requesterId);
}

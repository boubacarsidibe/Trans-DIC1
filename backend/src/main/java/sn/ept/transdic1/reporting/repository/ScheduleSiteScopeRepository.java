package sn.ept.transdic1.reporting.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import sn.ept.transdic1.reporting.domain.*;

public interface ScheduleSiteScopeRepository extends JpaRepository<ScheduleSiteScope, UUID> {
  List<ScheduleSiteScope> findByScheduleId(UUID scheduleId);
}

package sn.ept.transdic1.reporting.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import sn.ept.transdic1.reporting.domain.*;

public interface ReportScheduleRepository extends JpaRepository<ReportSchedule, UUID> {
  List<ReportSchedule> findByActiveTrueAndNextRunAtLessThanEqual(Instant now);
}

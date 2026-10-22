package sn.ept.transdic1.reporting.repository;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import sn.ept.transdic1.reporting.domain.*;

public interface ReportRepository extends JpaRepository<Report, UUID> {
  Page<Report> findByRequesterIdOrderByPeriodStartDesc(UUID requesterId, Pageable pageable);
}

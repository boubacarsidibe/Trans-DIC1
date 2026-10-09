package sn.ept.transdic1.monitoring.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import sn.ept.transdic1.monitoring.domain.Metric;

public interface MetricRepository extends JpaRepository<Metric, UUID> {
  List<Metric> findTop20ByOrderByCollectedAtDesc();
}

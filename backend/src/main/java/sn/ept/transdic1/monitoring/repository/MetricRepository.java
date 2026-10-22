package sn.ept.transdic1.monitoring.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import sn.ept.transdic1.monitoring.domain.Metric;

public interface MetricRepository extends JpaRepository<Metric, UUID> {
  List<Metric> findTop20ByOrderByCollectedAtDesc();

  /** Historique paginé d'un indicateur sur une période, pour les graphiques. */
  Page<Metric> findByEquipmentIdAndKeyAndCollectedAtBetweenOrderByCollectedAtAsc(
      UUID equipmentId, String key, Instant from, Instant to, Pageable pageable);

  Optional<Metric> findFirstByEquipmentIdAndKeyOrderByCollectedAtDesc(UUID equipmentId, String key);
}

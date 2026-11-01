package sn.ept.transdic1.monitoring.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import sn.ept.transdic1.monitoring.domain.Metric;

public interface MetricRepository extends JpaRepository<Metric, Long> {
  List<Metric> findTop20ByOrderByCollectedAtDesc();

  /** Les mêmes, limitées aux sites du périmètre d'accès (ADR 0018). */
  List<Metric> findTop20ByEquipment_Site_IdInOrderByCollectedAtDesc(Collection<UUID> siteIds);

  /** Historique paginé d'un indicateur sur une période, pour les graphiques. */
  Page<Metric> findByEquipmentIdAndKeyAndCollectedAtBetweenOrderByCollectedAtAsc(
      UUID equipmentId, String key, Instant from, Instant to, Pageable pageable);

  /** Mesures d'un indicateur sur une période, de la plus récente à la plus ancienne. */
  List<Metric> findByEquipmentIdAndKeyAndCollectedAtBetweenOrderByCollectedAtDesc(
      UUID equipmentId, String key, Instant from, Instant to, Pageable pageable);

  Optional<Metric> findFirstByEquipmentIdAndKeyOrderByCollectedAtDesc(UUID equipmentId, String key);
}

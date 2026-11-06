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

  /** Instances mesurées d'une famille (disk.usage[C:], disk.usage[D:]) sur une période. */
  @org.springframework.data.jpa.repository.Query(
      "select distinct m.key from Metric m where m.equipment.id = :equipmentId"
          + " and m.key like :prefix and m.collectedAt between :from and :to")
  List<String> findInstanceKeys(
      @org.springframework.data.repository.query.Param("equipmentId") UUID equipmentId,
      @org.springframework.data.repository.query.Param("prefix") String prefix,
      @org.springframework.data.repository.query.Param("from") Instant from,
      @org.springframework.data.repository.query.Param("to") Instant to);

  /** Les mêmes, limitées aux sites du périmètre d'accès (ADR 0018). */
  List<Metric> findTop20ByEquipment_Site_IdInOrderByCollectedAtDesc(Collection<UUID> siteIds);

  /** Historique paginé d'un indicateur sur une période, pour les graphiques. */
  Page<Metric> findByEquipmentIdAndKeyAndCollectedAtBetweenOrderByCollectedAtAsc(
      UUID equipmentId, String key, Instant from, Instant to, Pageable pageable);

  /**
   * Mesures sans service d'un indicateur sur une période, de la plus récente à la plus ancienne.
   */
  List<Metric> findByEquipmentIdAndServiceIsNullAndKeyAndCollectedAtBetweenOrderByCollectedAtDesc(
      UUID equipmentId, String key, Instant from, Instant to, Pageable pageable);

  /** Mesures d'un service sur une période, de la plus récente à la plus ancienne. */
  List<Metric> findByEquipmentIdAndServiceIdAndKeyAndCollectedAtBetweenOrderByCollectedAtDesc(
      UUID equipmentId, UUID serviceId, String key, Instant from, Instant to, Pageable pageable);

  Optional<Metric> findFirstByEquipmentIdAndKeyOrderByCollectedAtDesc(UUID equipmentId, String key);

  /** Vrai si un service a déjà des mesures (il ne peut alors plus être supprimé physiquement). */
  boolean existsByServiceId(UUID serviceId);
}

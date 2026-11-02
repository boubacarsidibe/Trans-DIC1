package sn.ept.transdic1.inventory.repository;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import sn.ept.transdic1.inventory.domain.*;

public interface MonitoredServiceRepository extends JpaRepository<MonitoredService, UUID> {
  List<MonitoredService> findByEquipmentIdAndActiveTrue(UUID equipmentId);

  /**
   * Services actifs des types donnés arrivés à échéance ; une échéance absente (service jamais
   * exécuté) compte comme échue. Les plus anciens d'abord, en nombre borné par {@code page}.
   */
  @Query(
      "select s.id from MonitoredService s where s.active = true and s.type in :types"
          + " and (s.nextRunAt is null or s.nextRunAt <= :now)"
          + " order by s.nextRunAt asc nulls first")
  List<UUID> findDueIds(
      @Param("types") Collection<ServiceType> types, @Param("now") Instant now, Pageable page);

  /** Relit un service en le verrouillant, pour qu'un seul passage le retienne. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select s from MonitoredService s where s.id = :id")
  Optional<MonitoredService> findForClaim(@Param("id") UUID id);

  List<MonitoredService> findByEquipmentIdOrderByName(UUID equipmentId);

  long countByEquipmentIdAndActiveTrue(UUID equipmentId);
}

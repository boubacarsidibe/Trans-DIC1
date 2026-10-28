package sn.ept.transdic1.alerting.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import sn.ept.transdic1.alerting.domain.*;

public interface MaintenanceRepository
    extends JpaRepository<Maintenance, UUID>,
        org.springframework.data.jpa.repository.JpaSpecificationExecutor<Maintenance> {
  /** Maintenances en cours à un instant donné pour un équipement ou pour son site. */
  @Query(
      "select m from Maintenance m where m.state in :states and m.startAt <= :at and m.endAt > :at "
          + "and (m.equipment.id = :equipmentId or m.site.id = :siteId)")
  List<Maintenance> findCovering(
      @Param("equipmentId") UUID equipmentId,
      @Param("siteId") UUID siteId,
      @Param("at") Instant at,
      @Param("states") Collection<MaintenanceState> states);

  List<Maintenance> findByStateOrderByStartAtAsc(MaintenanceState state);
}

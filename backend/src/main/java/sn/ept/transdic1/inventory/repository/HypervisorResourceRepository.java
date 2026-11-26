package sn.ept.transdic1.inventory.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import sn.ept.transdic1.inventory.domain.HypervisorResource;
import sn.ept.transdic1.inventory.domain.HypervisorResourceStatus;

public interface HypervisorResourceRepository extends JpaRepository<HypervisorResource, UUID> {
  @Query(
      "select r from HypervisorResource r left join fetch r.equipment"
          + " where r.connector.id = :connector and r.status = :status")
  List<HypervisorResource> findByConnectorAndStatus(
      @Param("connector") UUID connectorId, @Param("status") HypervisorResourceStatus status);

  @Query(
      "select r from HypervisorResource r left join fetch r.equipment"
          + " where r.connector.id = :connector order by r.kind, r.name")
  List<HypervisorResource> findByConnector(@Param("connector") UUID connectorId);

  /** Équipement déjà représenté par une ressource d'hyperviseur : on ne l'adopte pas deux fois. */
  boolean existsByEquipmentId(UUID equipmentId);
}

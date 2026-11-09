package sn.ept.transdic1.events.repository;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import sn.ept.transdic1.events.domain.NetworkEvent;

public interface NetworkEventRepository
    extends JpaRepository<NetworkEvent, UUID>, JpaSpecificationExecutor<NetworkEvent> {

  /** Liste filtrée, l'équipement chargé avec les événements (pas de requête par ligne). */
  @Override
  @EntityGraph(attributePaths = "equipment")
  Page<NetworkEvent> findAll(Specification<NetworkEvent> spec, Pageable pageable);

  /**
   * Supprime au plus {@code limit} événements reçus avant {@code before} (purge de rétention par
   * lots : verrous et journal de transactions bornés).
   */
  @Modifying
  @Query(
      value =
          "DELETE FROM network_event WHERE id IN (SELECT id FROM network_event"
              + " WHERE received_at < :before LIMIT :limit)",
      nativeQuery = true)
  int deleteReceivedBefore(@Param("before") Instant before, @Param("limit") int limit);
}

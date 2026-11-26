package sn.ept.transdic1.inventory.repository;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import sn.ept.transdic1.inventory.domain.HypervisorAction;
import sn.ept.transdic1.inventory.domain.HypervisorActionState;

public interface HypervisorActionRepository extends JpaRepository<HypervisorAction, UUID> {
  /** Verrouillée : deux administrateurs qui approuvent ensemble ne redémarrent pas deux fois. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select a from HypervisorAction a join fetch a.resource r join fetch r.connector c"
          + " join fetch c.site where a.id = :id")
  Optional<HypervisorAction> findForUpdate(@Param("id") UUID id);

  @Query(
      "select a from HypervisorAction a join fetch a.resource r"
          + " where r.connector.id = :connector order by a.createdAt desc")
  List<HypervisorAction> findByConnector(@Param("connector") UUID connectorId);

  boolean existsByResourceIdAndState(UUID resourceId, HypervisorActionState state);

  List<HypervisorAction> findByStateAndExpiresAtBefore(HypervisorActionState state, Instant now);
}

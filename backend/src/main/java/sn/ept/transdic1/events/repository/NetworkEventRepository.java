package sn.ept.transdic1.events.repository;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import sn.ept.transdic1.events.domain.NetworkEvent;

public interface NetworkEventRepository
    extends JpaRepository<NetworkEvent, UUID>, JpaSpecificationExecutor<NetworkEvent> {

  /** Supprime les événements reçus avant {@code before} (purge de rétention). */
  @Modifying
  @Query("delete from NetworkEvent e where e.receivedAt < :before")
  int deleteReceivedBefore(@Param("before") Instant before);
}

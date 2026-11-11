package sn.ept.transdic1.threat.repository;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import sn.ept.transdic1.threat.domain.SecurityEvent;

public interface SecurityEventRepository extends JpaRepository<SecurityEvent, UUID> {

  /**
   * Supprime au plus {@code limit} événements reçus avant {@code before} (purge par lots : les
   * verrous et le journal de transactions restent bornés, même après une rafale d'attaques).
   */
  @Modifying
  @Query(
      value =
          "DELETE FROM security_event WHERE id IN (SELECT id FROM security_event"
              + " WHERE received_at < :before LIMIT :limit)",
      nativeQuery = true)
  int deleteReceivedBefore(@Param("before") Instant before, @Param("limit") int limit);
}

package sn.ept.transdic1.reporting.repository;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import sn.ept.transdic1.reporting.domain.*;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {
  boolean existsByDedupKey(String dedupKey);

  Page<Notification> findAllByOrderByCreatedAtDesc(Pageable pageable);

  /** Notifications à envoyer ou à relancer, de la plus ancienne à la plus récente. */
  Page<Notification> findByStateOrderByCreatedAtAsc(NotificationState state, Pageable pageable);

  long countByState(NotificationState state);

  /** Date de création de la plus ancienne notification dans cet état (âge de la file). */
  @org.springframework.data.jpa.repository.Query(
      "select min(n.createdAt) from Notification n where n.state = :state")
  java.time.Instant oldestCreatedAt(
      @org.springframework.data.repository.query.Param("state") NotificationState state);
}

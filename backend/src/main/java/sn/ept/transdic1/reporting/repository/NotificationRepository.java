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
}

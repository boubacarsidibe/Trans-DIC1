package sn.ept.transdic1.reporting.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import sn.ept.transdic1.reporting.domain.*;

public interface NotificationAttemptRepository extends JpaRepository<NotificationAttempt, UUID> {
  List<NotificationAttempt> findByNotificationIdOrderByNumberAsc(UUID notificationId);

  long countByNotificationId(UUID notificationId);
}

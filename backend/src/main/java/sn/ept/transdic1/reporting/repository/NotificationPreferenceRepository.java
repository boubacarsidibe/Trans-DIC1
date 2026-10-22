package sn.ept.transdic1.reporting.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import sn.ept.transdic1.reporting.domain.*;

public interface NotificationPreferenceRepository
    extends JpaRepository<NotificationPreference, UUID> {
  Optional<NotificationPreference> findByUserId(UUID userId);
}

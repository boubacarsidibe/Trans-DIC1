package sn.ept.transdic1.security.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import sn.ept.transdic1.security.domain.*;

public interface UserSessionRepository extends JpaRepository<UserSession, UUID> {
  Optional<UserSession> findByTokenHashAndRevokedAtIsNull(String tokenHash);

  long countByUserIdAndRevokedAtIsNull(UUID userId);
}

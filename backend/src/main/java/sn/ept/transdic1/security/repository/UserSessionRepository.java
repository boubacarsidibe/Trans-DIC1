package sn.ept.transdic1.security.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import sn.ept.transdic1.security.domain.*;

public interface UserSessionRepository extends JpaRepository<UserSession, UUID> {
  Optional<UserSession> findByTokenHashAndRevokedAtIsNull(String tokenHash);

  Optional<UserSession> findByTokenHash(String tokenHash);

  /**
   * Sessions encore valides d'une famille, à révoquer en cas de réutilisation ou de déconnexion.
   */
  java.util.List<UserSession> findByFamilyIdAndRevokedAtIsNull(UUID familyId);

  long countByUserIdAndRevokedAtIsNull(UUID userId);
}

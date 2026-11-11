package sn.ept.transdic1.security.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import sn.ept.transdic1.security.domain.ApiToken;

public interface ApiTokenRepository extends JpaRepository<ApiToken, UUID> {
  Optional<ApiToken> findByTokenHash(String tokenHash);

  List<ApiToken> findByUserIdOrderByCreatedAtDesc(UUID userId);

  boolean existsByUserIdAndNameAndRevokedAtIsNull(UUID userId, String name);
}

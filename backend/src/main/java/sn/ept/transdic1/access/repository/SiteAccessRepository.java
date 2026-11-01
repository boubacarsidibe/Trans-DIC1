package sn.ept.transdic1.access.repository;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import sn.ept.transdic1.access.domain.*;

public interface SiteAccessRepository extends JpaRepository<SiteAccess, UUID> {
  List<SiteAccess> findByUserId(UUID userId);

  boolean existsByUserIdAndSiteId(UUID userId, UUID siteId);

  @Query("select a.site.id from SiteAccess a where a.user.username = :username")
  Set<UUID> findSiteIdsByUsername(@Param("username") String username);

  @Query("select a.site.id from SiteAccess a where a.user.id = :userId")
  Set<UUID> findSiteIdsByUserId(@Param("userId") UUID userId);

  void deleteByUserId(UUID userId);
}

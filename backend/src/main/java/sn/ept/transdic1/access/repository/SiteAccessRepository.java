package sn.ept.transdic1.access.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import sn.ept.transdic1.access.domain.*;

public interface SiteAccessRepository extends JpaRepository<SiteAccess, UUID> {
  List<SiteAccess> findByUserId(UUID userId);

  boolean existsByUserIdAndSiteId(UUID userId, UUID siteId);
}

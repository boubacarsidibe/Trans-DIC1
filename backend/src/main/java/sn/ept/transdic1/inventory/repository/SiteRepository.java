package sn.ept.transdic1.inventory.repository;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.domain.SiteStatus;

public interface SiteRepository extends JpaRepository<Site, UUID> {
  Optional<Site> findByCode(String code);

  Page<Site> findByStatus(SiteStatus status, Pageable pageable);

  Page<Site> findByIdIn(Collection<UUID> ids, Pageable pageable);

  Page<Site> findByStatusAndIdIn(SiteStatus status, Collection<UUID> ids, Pageable pageable);
}

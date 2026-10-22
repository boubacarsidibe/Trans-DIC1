package sn.ept.transdic1.inventory.repository;

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
}

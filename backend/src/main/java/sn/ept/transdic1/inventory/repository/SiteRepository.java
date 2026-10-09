package sn.ept.transdic1.inventory.repository;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import sn.ept.transdic1.inventory.domain.Site;

public interface SiteRepository extends JpaRepository<Site, UUID> {}

package sn.ept.transdic1.inventory.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import sn.ept.transdic1.inventory.domain.HypervisorConnector;

public interface HypervisorConnectorRepository extends JpaRepository<HypervisorConnector, UUID> {
  @Query("select c from HypervisorConnector c join fetch c.site order by c.name")
  List<HypervisorConnector> findAllWithSite();

  List<HypervisorConnector> findByEnabledTrue();
}

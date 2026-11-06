package sn.ept.transdic1.statuspage.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import sn.ept.transdic1.statuspage.domain.StatusComponent;

public interface StatusComponentRepository extends JpaRepository<StatusComponent, UUID> {
  List<StatusComponent> findByPageIdOrderByPositionAscDisplayNameAsc(UUID pageId);

  long countByPageId(UUID pageId);
}

package sn.ept.transdic1.statuspage.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import sn.ept.transdic1.statuspage.domain.StatusPage;
import sn.ept.transdic1.statuspage.domain.StatusVisibility;

public interface StatusPageRepository extends JpaRepository<StatusPage, UUID> {
  Optional<StatusPage> findBySlug(String slug);

  boolean existsBySlug(String slug);

  boolean existsBySlugAndIdNot(String slug, UUID id);

  List<StatusPage> findAllByOrderByTitleAsc();

  List<StatusPage> findByActiveTrueOrderByTitleAsc();

  List<StatusPage> findByActiveTrueAndVisibility(StatusVisibility visibility);
}

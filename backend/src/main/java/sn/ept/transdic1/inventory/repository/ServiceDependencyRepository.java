package sn.ept.transdic1.inventory.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import sn.ept.transdic1.inventory.domain.ServiceDependency;

public interface ServiceDependencyRepository extends JpaRepository<ServiceDependency, UUID> {
  @Query(
      "select d from ServiceDependency d join fetch d.service s join fetch s.equipment se"
          + " join fetch se.site join fetch d.dependsOn o join fetch o.equipment oe"
          + " join fetch oe.site order by se.name, s.name")
  List<ServiceDependency> findAllWithServices();

  /** Arêtes du graphe (service → service dont il dépend) : de quoi détecter un cycle. */
  @Query("select d.service.id, d.dependsOn.id from ServiceDependency d")
  List<Object[]> edges();

  boolean existsByServiceIdAndDependsOnId(UUID serviceId, UUID dependsOnId);
}

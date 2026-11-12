package sn.ept.transdic1.inventory.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import sn.ept.transdic1.inventory.domain.LldpNeighbour;

public interface LldpNeighbourRepository extends JpaRepository<LldpNeighbour, UUID> {
  List<LldpNeighbour> findByEquipmentId(UUID equipmentId);

  /** Voisins non écartés, pour l'écran de topologie. */
  List<LldpNeighbour> findByIgnoredFalse();
}

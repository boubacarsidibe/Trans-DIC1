package sn.ept.transdic1.inventory.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import sn.ept.transdic1.inventory.domain.*;

public interface CollectionConfigurationRepository
    extends JpaRepository<CollectionConfiguration, UUID> {
  List<CollectionConfiguration> findByEquipmentId(UUID equipmentId);

  /** Configurations actives dont l'échéance est atteinte : entrée du planificateur de collecte. */
  List<CollectionConfiguration> findByActiveTrueAndNextRunAtLessThanEqual(Instant now);
}

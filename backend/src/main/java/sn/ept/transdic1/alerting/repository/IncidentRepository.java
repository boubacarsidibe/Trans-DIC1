package sn.ept.transdic1.alerting.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import sn.ept.transdic1.alerting.domain.Incident;
import sn.ept.transdic1.alerting.domain.IncidentState;

public interface IncidentRepository extends JpaRepository<Incident, UUID> {

  /** Incidents ouverts dont la dernière alerte est assez récente pour accueillir la suivante. */
  List<Incident> findByStateAndLastAlertAtGreaterThanEqual(IncidentState state, Instant since);

  Page<Incident> findByState(IncidentState state, Pageable pageable);

  Page<Incident> findBySiteIdIn(java.util.Collection<UUID> siteIds, Pageable pageable);

  Page<Incident> findByStateAndSiteIdIn(
      IncidentState state, java.util.Collection<UUID> siteIds, Pageable pageable);
}

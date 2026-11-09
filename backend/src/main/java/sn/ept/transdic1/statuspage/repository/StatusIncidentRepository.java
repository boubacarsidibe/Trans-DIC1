package sn.ept.transdic1.statuspage.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import sn.ept.transdic1.statuspage.domain.IncidentStatus;
import sn.ept.transdic1.statuspage.domain.StatusIncident;

public interface StatusIncidentRepository extends JpaRepository<StatusIncident, UUID> {
  /** Incidents d'une page, les plus récents d'abord, en nombre borné. */
  List<StatusIncident> findByPageIdOrderByCreatedAtDesc(UUID pageId, Pageable page);

  /**
   * Incidents encore ouverts, ou créés ou résolus depuis {@code since}, les plus récents d'abord.
   */
  @Query(
      "select i from StatusIncident i where i.pageId = :pageId and (i.status <> :resolved"
          + " or i.createdAt >= :since or i.resolvedAt >= :since) order by i.createdAt desc")
  List<StatusIncident> findRecent(
      @Param("pageId") UUID pageId,
      @Param("resolved") IncidentStatus resolved,
      @Param("since") Instant since,
      Pageable page);

  long countByPageId(UUID pageId);

  /** Incident automatique encore ouvert d'un composant. */
  Optional<StatusIncident> findFirstByAutoComponentIdAndStatusNot(
      UUID componentId, IncidentStatus status);

  List<StatusIncident> findByAutoComponentIdIn(Collection<UUID> componentIds);
}

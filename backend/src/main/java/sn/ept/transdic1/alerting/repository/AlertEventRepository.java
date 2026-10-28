package sn.ept.transdic1.alerting.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import sn.ept.transdic1.alerting.domain.*;

public interface AlertEventRepository extends JpaRepository<AlertEvent, UUID> {
  List<AlertEvent> findByAlertIdOrderByOccurredAtAsc(UUID alertId);

  /**
   * Événements d'ouverture et de clôture des alertes d'un type jusqu'à une date, regroupés par
   * alerte puis dans l'ordre du temps : de quoi reconstituer chaque coupure.
   */
  @org.springframework.data.jpa.repository.Query(
      "select e from AlertEvent e join fetch e.alert a join fetch a.equipment q join fetch q.site"
          + " where a.type = :type and e.type in :eventTypes and e.occurredAt < :until"
          + " order by a.id asc, e.occurredAt asc")
  List<AlertEvent> findOutageEvents(
      @org.springframework.data.repository.query.Param("type") AlertType type,
      @org.springframework.data.repository.query.Param("eventTypes")
          java.util.Collection<AlertEventType> eventTypes,
      @org.springframework.data.repository.query.Param("until") java.time.Instant until);
}

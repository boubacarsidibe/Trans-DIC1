package sn.ept.transdic1.oncall.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import sn.ept.transdic1.alerting.domain.AlertState;
import sn.ept.transdic1.oncall.domain.OnCallAlertStep;
import sn.ept.transdic1.oncall.domain.OnCallLevel;

public interface OnCallAlertStepRepository extends JpaRepository<OnCallAlertStep, UUID> {
  boolean existsByAlertIdAndLevel(UUID alertId, OnCallLevel level);

  List<OnCallAlertStep> findByAlertIdOrderByOccurredAtAsc(UUID alertId);

  /**
   * Étapes « principal » des alertes encore dans l'état donné dont le niveau secondaire n'a pas
   * encore été franchi : les candidates du passage planifié.
   */
  @Query(
      "select p from OnCallAlertStep p, Alert a where a.id = p.alertId"
          + " and p.level = sn.ept.transdic1.oncall.domain.OnCallLevel.PRIMARY"
          + " and a.state = :state and not exists (select 1 from OnCallAlertStep s"
          + " where s.alertId = p.alertId"
          + " and s.level = sn.ept.transdic1.oncall.domain.OnCallLevel.SECONDARY)")
  List<OnCallAlertStep> findAwaitingSecondary(@Param("state") AlertState state);
}

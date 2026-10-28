package sn.ept.transdic1.alerting.repository;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import sn.ept.transdic1.alerting.domain.*;

public interface AlertRepository
    extends JpaRepository<Alert, UUID>,
        org.springframework.data.jpa.repository.JpaSpecificationExecutor<Alert> {
  Optional<Alert> findByOccurrenceKey(String occurrenceKey);

  /** Alerte déjà active pour un couple équipement-règle : évite d'ouvrir un doublon. */
  Optional<Alert> findFirstByEquipmentIdAndRuleIdAndStateIn(
      UUID equipmentId, UUID ruleId, Collection<AlertState> states);

  /** Dernière alerte résolue du couple équipement-règle, rouverte si l'anomalie revient. */
  Optional<Alert> findFirstByEquipmentIdAndRuleIdAndStateOrderByResolvedAtDesc(
      UUID equipmentId, UUID ruleId, AlertState state);

  boolean existsByRuleId(UUID ruleId);

  /** Alertes dans ces états, par sévérité : {sévérité, total}. */
  @org.springframework.data.jpa.repository.Query(
      "select a.severity, count(a) from Alert a where a.state in :states group by a.severity")
  java.util.List<Object[]> countBySeverityForStates(
      @org.springframework.data.repository.query.Param("states") Collection<AlertState> states);

  Page<Alert> findByState(AlertState state, Pageable pageable);

  Page<Alert> findByEquipmentIdOrderByTriggeredAtDesc(UUID equipmentId, Pageable pageable);

  long countByStateIn(Collection<AlertState> states);
}

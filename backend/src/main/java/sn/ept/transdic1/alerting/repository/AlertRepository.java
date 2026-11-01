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

  /** Alerte active dont la clé d'occurrence commence par ce préfixe (anomalies). */
  Optional<Alert> findFirstByOccurrenceKeyStartingWithAndStateIn(
      String prefix, Collection<AlertState> states);

  /**
   * Alertes toujours ouvertes (personne ne les a prises) depuis avant {@code before}, pas encore
   * escaladées.
   */
  java.util.List<Alert> findByStateAndEscalatedAtIsNullAndTriggeredAtBefore(
      AlertState state, java.time.Instant before);

  /** Alertes déclenchées pendant la période [from, to[, les plus récentes d'abord. */
  java.util.List<Alert>
      findByTriggeredAtGreaterThanEqualAndTriggeredAtLessThanOrderByTriggeredAtDesc(
          java.time.Instant from, java.time.Instant to);

  /** Alerte de collecte active (sans règle) d'un équipement. */
  Optional<Alert> findFirstByEquipmentIdAndTypeAndStateIn(
      UUID equipmentId, AlertType type, Collection<AlertState> states);

  /**
   * Dernière alerte de collecte résolue, rouverte si l'agent se tait de nouveau avant archivage.
   */
  Optional<Alert> findFirstByEquipmentIdAndTypeAndStateOrderByResolvedAtDesc(
      UUID equipmentId, AlertType type, AlertState state);

  /** Alertes dans ces états, par sévérité : {sévérité, total}. */
  @org.springframework.data.jpa.repository.Query(
      "select a.severity, count(a) from Alert a where a.state in :states group by a.severity")
  java.util.List<Object[]> countBySeverityForStates(
      @org.springframework.data.repository.query.Param("states") Collection<AlertState> states);

  Page<Alert> findByState(AlertState state, Pageable pageable);

  Page<Alert> findByEquipmentIdOrderByTriggeredAtDesc(UUID equipmentId, Pageable pageable);

  long countByStateIn(Collection<AlertState> states);

  /** Les deux comptages du tableau de bord, limités au périmètre d'accès (ADR 0018). */
  @org.springframework.data.jpa.repository.Query(
      "select a.severity, count(a) from Alert a where a.state in :states"
          + " and a.equipment.site.id in :sites group by a.severity")
  java.util.List<Object[]> countBySeverityForStatesInSites(
      @org.springframework.data.repository.query.Param("states") Collection<AlertState> states,
      @org.springframework.data.repository.query.Param("sites") Collection<UUID> sites);

  long countByStateInAndEquipment_Site_IdIn(Collection<AlertState> states, Collection<UUID> sites);
}

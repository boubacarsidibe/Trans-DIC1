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

  /** Alerte active d'un service pour une règle (sondes). */
  Optional<Alert> findFirstByEquipmentIdAndRuleIdAndServiceIdAndStateIn(
      UUID equipmentId, UUID ruleId, UUID serviceId, Collection<AlertState> states);

  /** Alerte active d'une règle pour les mesures sans service ni instance. */
  Optional<Alert> findFirstByEquipmentIdAndRuleIdAndServiceIsNullAndMetricInstanceIsNullAndStateIn(
      UUID equipmentId, UUID ruleId, Collection<AlertState> states);

  /** Alerte active d'une règle pour un disque ou une carte. */
  Optional<Alert> findFirstByEquipmentIdAndRuleIdAndServiceIsNullAndMetricInstanceAndStateIn(
      UUID equipmentId, UUID ruleId, String metricInstance, Collection<AlertState> states);

  /** Dernière alerte résolue d'un service pour une règle. */
  Optional<Alert> findFirstByEquipmentIdAndRuleIdAndServiceIdAndStateOrderByResolvedAtDesc(
      UUID equipmentId, UUID ruleId, UUID serviceId, AlertState state);

  /** Dernière alerte résolue d'une règle pour les mesures sans service ni instance. */
  Optional<Alert>
      findFirstByEquipmentIdAndRuleIdAndServiceIsNullAndMetricInstanceIsNullAndStateOrderByResolvedAtDesc(
          UUID equipmentId, UUID ruleId, AlertState state);

  /** Dernière alerte résolue d'une règle pour un disque ou une carte. */
  Optional<Alert>
      findFirstByEquipmentIdAndRuleIdAndServiceIsNullAndMetricInstanceAndStateOrderByResolvedAtDesc(
          UUID equipmentId, UUID ruleId, String metricInstance, AlertState state);

  /**
   * Alerte active du triplet équipement-règle-service ; {@code serviceId} nul désigne les mesures
   * sans service (requête explicite {@code service IS NULL}).
   */
  default Optional<Alert> findActiveForRule(
      UUID equipmentId, UUID ruleId, UUID serviceId, Collection<AlertState> states) {
    return findActiveForRule(equipmentId, ruleId, serviceId, null, states);
  }

  default Optional<Alert> findActiveForRule(
      UUID equipmentId,
      UUID ruleId,
      UUID serviceId,
      String metricInstance,
      Collection<AlertState> states) {
    if (serviceId != null) {
      return findFirstByEquipmentIdAndRuleIdAndServiceIdAndStateIn(
          equipmentId, ruleId, serviceId, states);
    }
    return metricInstance == null
        ? findFirstByEquipmentIdAndRuleIdAndServiceIsNullAndMetricInstanceIsNullAndStateIn(
            equipmentId, ruleId, states)
        : findFirstByEquipmentIdAndRuleIdAndServiceIsNullAndMetricInstanceAndStateIn(
            equipmentId, ruleId, metricInstance, states);
  }

  /** Dernière alerte résolue du triplet équipement-règle-service (service nul compris). */
  default Optional<Alert> findLastResolvedForRule(
      UUID equipmentId, UUID ruleId, UUID serviceId, String metricInstance) {
    if (serviceId != null) {
      return findFirstByEquipmentIdAndRuleIdAndServiceIdAndStateOrderByResolvedAtDesc(
          equipmentId, ruleId, serviceId, AlertState.RESOLVED);
    }
    return metricInstance == null
        ? findFirstByEquipmentIdAndRuleIdAndServiceIsNullAndMetricInstanceIsNullAndStateOrderByResolvedAtDesc(
            equipmentId, ruleId, AlertState.RESOLVED)
        : findFirstByEquipmentIdAndRuleIdAndServiceIsNullAndMetricInstanceAndStateOrderByResolvedAtDesc(
            equipmentId, ruleId, metricInstance, AlertState.RESOLVED);
  }

  boolean existsByRuleId(UUID ruleId);

  /** Alerte EVENT active d'une règle d'événement sur un équipement (déduplication). */
  Optional<Alert> findFirstByEquipmentIdAndEventRuleIdAndStateIn(
      UUID equipmentId, UUID eventRuleId, Collection<AlertState> states);

  /** Dernière alerte EVENT résolue (non archivée) d'une règle d'événement sur un équipement. */
  Optional<Alert> findFirstByEquipmentIdAndEventRuleIdAndStateOrderByResolvedAtDesc(
      UUID equipmentId, UUID eventRuleId, AlertState state);

  boolean existsByEventRuleId(UUID eventRuleId);

  boolean existsByServiceId(UUID serviceId);

  /** Alertes d'un service dans ces états (sonde désactivée). */
  java.util.List<Alert> findByServiceIdAndStateIn(UUID serviceId, Collection<AlertState> states);

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
